package gw.proxy

import gw.wait
import io.netty.buffer.ByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.HttpObject
import io.netty.util.AttributeKey
import kotlinx.coroutines.experimental.launch
import org.slf4j.LoggerFactory
import java.util.*

class HttpPipeline(
    val server: Channel,
    val alloc: ByteBufAllocator
) {
    val requestResponses = ArrayDeque<GwRequestResponse>()

    private var shouldCloseServer = false

    fun pushRequestResponse(shouldClose: Boolean): GwRequestResponse {
        val response = GwRequestResponse(this)
        if (requestResponses.isEmpty()) response.activate()
        requestResponses.addLast(response)

        if (shouldClose) {
            if (shouldCloseServer) {
                throw RuntimeException("bad pipeline")
            }
            shouldCloseServer = true
        }

        return response
    }

    suspend fun popRequestResponse() {
        requestResponses.removeFirst().finish()
        requestResponses.firstOrNull()?.activate()
        if (requestResponses.isEmpty()) {
            finishPipeline()
        }
        flowControl()
    }

    fun sendClient(msg: HttpObject) {
        val reqResp = requestResponses.firstOrNull()
                ?: throw RuntimeException("illegal state")

        reqResp.sendClientWhenConnected(msg)
    }

    fun flushClient() {
        requestResponses.firstOrNull()?.flushClient()
    }

    private suspend fun finishPipeline() {
        server.flush()
        flowControl()
        if (shouldCloseServer) {
            server.close().wait()
        }
    }


    fun flowControl() {
        if (requestResponses.isEmpty()) {
            server.config().isAutoRead = true
            return
        }

        server.config().isAutoRead = requestResponses.first.isWriteable
        requestResponses.first.setClientAutoRead(server.isWritable)
        requestResponses.asSequence().drop(1).forEach { it.setClientAutoRead(false) }
    }

    fun serverExceptionHappened(cause: Throwable) {
        log.error("Server-side channel error. Aborting HTTP pipeline", cause)
        launch {
            abort()
        }
    }

    suspend fun abort() {
        requestResponses.forEach { it.finish(true) }
        server.close()
    }

    companion object {
        val attributeKey = AttributeKey.newInstance<HttpPipeline>("httpPipelineSeries")
        val log = LoggerFactory.getLogger(HttpPipeline::class.java)
    }
}

fun ChannelHandlerContext.httpPipelineSeries(): HttpPipeline {
    return channel().attr(HttpPipeline.attributeKey).get()
            ?: throw RuntimeException("illegal state")
}

