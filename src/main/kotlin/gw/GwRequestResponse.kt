package gw

import gw.GwRequestResponse.Direction.DOWNSTREAM
import gw.GwRequestResponse.Direction.UPSTREAM
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.pool.ChannelPool
import io.netty.channel.pool.ChannelPoolMap
import io.netty.handler.codec.http.*
import io.netty.util.AttributeKey
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.launch
import org.slf4j.LoggerFactory
import java.nio.charset.Charset
import java.util.*

class GwRequestResponse(
    private val serverCtx: ChannelHandlerContext
) {
    enum class Direction {
        UPSTREAM, DOWNSTREAM
    }

    private val server = serverCtx.channel()
    private var client: Channel? = null
    private var clientPool: ChannelPool? = null
    private var connectJob: Job? = null

    private var shouldCloseClient = false
    private var shouldCloseServer = false

    private var responseSent = false

    val dispatcher = server.eventLoop().asCoroutineDispatcher()

    companion object {
        val attributeKey = AttributeKey.newInstance<GwRequestResponse>("requestResponse")
        val utf8 = Charset.forName("UTF-8")
        val log = LoggerFactory.getLogger(GwRequestResponse::class.java)
    }


    fun handleConnectionClose(headers: HttpHeaders, direction: Direction) {
        if (headers.contains(
                HttpHeaderNames.CONNECTION,
                HttpHeaderValues.CLOSE,
                true
            )
        ) {
            headers.remove(HttpHeaderNames.CONNECTION)

            if (direction == DOWNSTREAM) {
                shouldCloseClient = true
            } else {
                shouldCloseServer = true
            }
        }
    }

    private val clientQueue = ArrayDeque<HttpObject>(2)

    fun send(msg: HttpObject, direction: Direction) =
        when (direction) {
            DOWNSTREAM -> {
                val cl = client
                if (cl == null) {
                    clientQueue.add(msg)
                    server.newSucceededFuture()
                } else {
                    while (clientQueue.isNotEmpty()){
                        cl.write(clientQueue.remove())
                    }
                    cl.write(msg)
                }
            }
            UPSTREAM -> {
                if (msg is HttpResponse) {
                    responseSent = true
                }
                server.write(msg)
            }
        }

    fun flush(direction: Direction) {
        when (direction) {
            DOWNSTREAM -> client?.flush()
            UPSTREAM -> server.flush()
        }
    }

    fun connectClient(
        request: HttpRequest,
        poolMap: ChannelPoolMap<GwPoolKey, ChannelPool>,
        results: List<GwRewriteResult>
    ) {
        connectJob = launch(dispatcher) {
            try {
                val (channel, pool) = connectOneByOne(results, poolMap)

                client = channel
                clientPool = pool

                channel.attr(GwRequestResponse.attributeKey).set(this@GwRequestResponse)

                handleConnectionClose(request.headers(), UPSTREAM)
                send(request, DOWNSTREAM)
                flush(DOWNSTREAM)
                flowControl()
            } catch (ex: Throwable) {
                server.write(ex)
            }
        }
    }

    private suspend fun connectOneByOne(
        results: List<GwRewriteResult>,
        poolMap: ChannelPoolMap<GwPoolKey, ChannelPool>
    ): Pair<Channel, ChannelPool> {
        var ex: Throwable? = null
        for (result in results) {
            try {
                val pool = poolMap[result.poolKey]
                val channel = pool.acquire().wait()

                return Pair(channel, pool)
            } catch (e: Throwable) {
                ex = e
            }
        }
        throw ex!!
    }

    fun exceptionHappened(cause: Throwable, direction: Direction) {
        if (direction == DOWNSTREAM) {
            launch(dispatcher) {
                done(closeClient = true)
                server.write(cause)
            }
            return
        }

        if (responseSent) {
            launch(dispatcher) {
                done(closeServer = true, closeClient = true)
            }
            return
        }
        responseSent = true

        val respose = GwErrorResponseBuilder.buildErrorResponse(serverCtx.alloc(), cause)

        val future = server.writeAndFlush(respose)
        launch(dispatcher) {
            future.wait()
            server.close()
        }

        client?.write(cause)
    }


    fun flowControl() {
        client?.config()?.isAutoRead = server.isWritable
        client?.isWritable?.let { server.config().isAutoRead = it }
    }


    suspend fun done(closeServer: Boolean = false, closeClient: Boolean = false) {
        connectJob?.cancel()
        connectJob?.join()

        client?.config()?.isAutoRead = true
        server.config().isAutoRead = true

        client?.attr(GwRequestResponse.attributeKey)?.set(null)
        server.attr(GwRequestResponse.attributeKey).set(null)

        if (shouldCloseClient or closeClient) {
            client?.close()?.wait()
        }
        if (shouldCloseServer or closeServer) {
            server.close()
        }
        releaseToPool()
    }

    private fun releaseToPool() {
        if (client != null) {
            clientPool?.release(client)
        }
        clientPool = null
        client = null
    }

}

