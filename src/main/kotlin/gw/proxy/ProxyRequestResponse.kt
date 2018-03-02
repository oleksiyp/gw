package gw.proxy

import gw.error.BadGatewayResponseBuilder
import gw.client.HttpClientPoolKey
import gw.rewrite.ProxyRewriteResult
import gw.wait
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
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
    val pipeline: HttpPipeline
) {
    private var client: Channel? = null
    private var clientPool: ChannelPool? = null
    private var connectJob: Job? = null

    private var shouldCloseClient = false

    private var responseSent = false

    private val server
        get() = pipeline.server

    private var active: Boolean = false

    val dispatcher = server.eventLoop().asCoroutineDispatcher()

    companion object {
        val attributeKey = AttributeKey.newInstance<GwRequestResponse>("requestResponse")
        val utf8 = Charset.forName("UTF-8")
        val log = LoggerFactory.getLogger(GwRequestResponse::class.java)
    }

    private val clientQueue = ArrayDeque<HttpObject>(2)
    private val serverQueue = ArrayDeque<HttpObject>(2)

    fun sendServerWhenActive(msg: HttpObject): ChannelFuture {
        return if (!active) {
            serverQueue.add(msg)
            server.newSucceededFuture()
        } else {
            drainServerQueue()
            sendServer(msg)
        }
    }

    private fun drainServerQueue() {
        val doFlush = serverQueue.isNotEmpty()
        while (serverQueue.isNotEmpty()) {
            sendServer(serverQueue.remove())
        }
        if (doFlush) {
            server.flush()
        }
    }

    private fun sendServer(msg: HttpObject): ChannelFuture {
        if (msg is HttpResponse) {
            responseSent = true
        }
        val future = server.write(msg)
        if (msg is LastHttpContent) {
            launch(dispatcher) {
                future.wait()
                pipeline.popRequestResponse()
            }
        }
        return future
    }

    fun sendClientWhenConnected(msg: HttpObject): ChannelFuture {
        val cl = client
        return if (cl == null) {
            clientQueue.add(msg)
            server.newSucceededFuture()
        } else {
            drainClientQueue(cl)
            cl.write(msg)
        }
    }

    private fun drainClientQueue(ch: Channel) {
        val doFlush = clientQueue.isNotEmpty()
        while (clientQueue.isNotEmpty()) {
            ch.write(clientQueue.remove())
        }
        if (doFlush) {
            ch.flush()
        }
    }

    fun flushServer() {
        server.flush()
    }

    fun flushClient() {
        client?.flush()
    }

    fun connectClient(
        request: HttpRequest,
        poolMap: ChannelPoolMap<HttpClientPoolKey, ChannelPool>,
        results: List<ProxyRewriteResult>
    ) {
        connectJob = launch(dispatcher) {
            try {
                val (channel, pool) = connectOneByOne(results, poolMap)

                client = channel
                clientPool = pool

                if (!HttpUtil.isKeepAlive(request)) {
                    HttpUtil.setKeepAlive(request, true)
                    shouldCloseClient = true
                }

                channel.attr(GwRequestResponse.attributeKey).set(this@GwRequestResponse)
                pipeline.flowControl()
                channel.writeAndFlush(request)
                drainClientQueue(channel)
            } catch (ex: Throwable) {
                server.write(ex)
            }
        }
    }

    private suspend fun connectOneByOne(
        results: List<ProxyRewriteResult>,
        poolMap: ChannelPoolMap<HttpClientPoolKey, ChannelPool>
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

    fun clientExceptionHappened(cause: Throwable) {
        launch(dispatcher) {
            if (responseSent) {
                log.error("Client-side channel error. Aborting HTTP pipeline", cause)
                pipeline.abort()
            } else {
                log.error("Client-side channel error. Sending error response", cause)
                val respose = BadGatewayResponseBuilder.buildErrorResponse(pipeline.alloc, cause)
                sendServerWhenActive(respose)
                sendServerWhenActive(DefaultLastHttpContent())
                finish(true)
            }
        }
    }


    fun setClientAutoRead(readClient: Boolean) {
        client?.config()?.isAutoRead = readClient
    }

    suspend fun finish(closeClient: Boolean = false) {
        connectJob?.cancel()
        connectJob?.join()

        client?.config()?.isAutoRead = true
        client?.attr(attributeKey)?.set(null)
        if (shouldCloseClient or closeClient) {
            client?.close()?.wait()
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

    val isWriteable: Boolean get() = if (active) client?.isWritable ?: false else false

    fun activate() {
        active = true
        drainServerQueue()
    }
}


fun ChannelHandlerContext.requestResponse(): GwRequestResponse =
    channel().attr(GwRequestResponse.attributeKey)?.get()
            ?: throw RuntimeException("bad state")
