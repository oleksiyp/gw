package gw.proxy

import gw.error.BadGatewayResponseBuilder
import gw.client.HttpClientPoolKey
import gw.rewrite.ProxyRewriteResult
import gw.wait
import io.netty.buffer.ByteBufAllocator
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

class ProxyHttpRequestResponse(
    val server: Channel,
    val alloc: ByteBufAllocator
) {
    private var client: Channel? = null
    private var clientPool: ChannelPool? = null
    private var connectJob: Job? = null

    private var shouldCloseClient = false
    var shouldCloseServer = false

    private var responseSent = false

    val dispatcher = server.eventLoop().asCoroutineDispatcher()

    companion object {
        val attributeKey = AttributeKey.newInstance<ProxyHttpRequestResponse>("requestResponse")
        val log = LoggerFactory.getLogger(ProxyHttpRequestResponse::class.java)
    }

    private val clientQueue = ArrayDeque<HttpObject>(2)

    private var finishOk: Boolean = false

    fun sendServer(msg: HttpObject): ChannelFuture {
        if (msg is HttpResponse) {
            responseSent = true
            if (!HttpUtil.isKeepAlive(msg)) {
                HttpUtil.setKeepAlive(msg, true)
                shouldCloseClient = true
            }
        }
        val future = server.write(msg)
        if (msg is LastHttpContent) {
            finishOk = true
            launch(dispatcher) {
                future.wait()
                finish()
            }
        }
        return future
    }

    fun sendClient(msg: HttpObject): ChannelFuture {
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
        flowControl()
        connectJob = launch(dispatcher) {
            try {
                val (channel, pool) = connectOneByOne(results, poolMap)
                channel.attr(ProxyHttpRequestResponse.attributeKey).set(this@ProxyHttpRequestResponse)

                client = channel
                clientPool = pool

                if (!HttpUtil.isKeepAlive(request)) {
                    HttpUtil.setKeepAlive(request, true)
                    shouldCloseServer = true
                }

                flowControl()
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

    fun flowControl() {
        server.config().isAutoRead = client?.isWritable ?: true
        client?.config()?.isAutoRead = server.isWritable
    }

    suspend fun finish(closeClient: Boolean = false) {
        connectJob?.cancel()
        connectJob?.join()

        val ch = client
        client = null

        if (ch != null) {
            releaseToPool(ch, shouldCloseClient or closeClient)
        }

        server.flush()

        if (shouldCloseServer) {
            server.close().wait()
        }
    }

    private suspend fun releaseToPool(ch: Channel, close: Boolean) {
        ch.config().isAutoRead = true
        ch.attr(attributeKey).set(null)
        if (close) {
            ch.close().wait()
        }
        clientPool?.release(ch)
        clientPool = null
    }

    fun clientExceptionHappened(cause: Throwable) {
        launch(dispatcher) {
            if (responseSent) {
                log.error("Client-side channel error. Response sent. Closing connections", cause)
                closeServer()
            } else {
                log.error("Client-side channel error. Sending error response", cause)
                val respose = BadGatewayResponseBuilder.buildErrorResponse(alloc, cause)
                sendServer(respose)
                sendServer(DefaultLastHttpContent())
                finish(true)
            }
        }
    }

    fun serverExceptionHappened(cause: Throwable) {
        log.error("Server-side channel error. Closing connections", cause)
        launch {
            closeServer()
        }
    }

    suspend fun closeServer() {
        finish(true)
        server.close()
    }

    fun canHandleNextRequest() = finishOk && !shouldCloseServer

}

var ChannelHandlerContext.requestResponseNullable: ProxyHttpRequestResponse?
    get () = channel().attr(ProxyHttpRequestResponse.attributeKey).get()
    set(value) = channel().attr(ProxyHttpRequestResponse.attributeKey).set(value)

var ChannelHandlerContext.requestResponse: ProxyHttpRequestResponse
    get () = channel().attr(ProxyHttpRequestResponse.attributeKey).get()
            ?: throw RuntimeException("bad state")
    set(value) = channel().attr(ProxyHttpRequestResponse.attributeKey).set(value)
