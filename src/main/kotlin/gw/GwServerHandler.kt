package gw

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.pool.ChannelPool
import io.netty.channel.pool.ChannelPoolMap
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpRequest
import io.netty.util.AttributeKey
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.launch
import java.net.InetSocketAddress
import java.net.URI
import java.util.*
import java.util.concurrent.LinkedBlockingQueue

class GwServerHandler(
    private val poolMap: ChannelPoolMap<GwPoolKey, ChannelPool>,
    private val rewriteRules: GwRewriteRules,
    val preConnectQueueSize: Int
) : ChannelInboundHandlerAdapter() {
    companion object {
        val preConnectQueueAttributeKey =
            AttributeKey.newInstance<Queue<HttpContent>>("preConnectQueueSize")
    }

    suspend fun openClientConnection(serverCh: Channel, httpRequest: HttpRequest): GwRequestResponse {
        val rewrittenUri = rewriteRules.    rewrite(httpRequest.uri())
        val target = URI(rewrittenUri)
        val parser = HttpURLParser(target)
        val targetAddr = InetSocketAddress(parser.host, parser.port)
        val secure = target.scheme.equals("https", ignoreCase = true)
        val poolKey = GwPoolKey(targetAddr, secure)
        val pool = poolMap.get(poolKey)

        val clientCh = pool.acquire().wait()

        httpRequest.headers().set("Host", parser.host)

        val reqResp = GwRequestResponse(serverCh, clientCh, pool)

        serverCh.attr(GwRequestResponse.attributeKey).set(reqResp)
        clientCh.attr(GwRequestResponse.attributeKey).set(reqResp)

        return reqResp
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        ctx.flush()
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is HttpRequest) {
            ReferenceCountUtil.retain(msg)
            ctx.channel().config().isAutoRead = false
            ctx.channel().attr(preConnectQueueAttributeKey)
                .set(LinkedBlockingQueue(preConnectQueueSize))

            launch {
                val reqResp = openClientConnection(ctx.channel(), msg)

                reqResp.client.writeAndFlush(msg)
                    .wait()

                val queue = ctx.channel().attr(preConnectQueueAttributeKey)
                    .getAndSet(null)

                drainPreConnectQueue(queue, reqResp.client)

                ctx.channel().config().isAutoRead = true
            }
        }
        if (msg is HttpContent) {
            ReferenceCountUtil.retain(msg)
            if (enqueuIfConnecting(ctx, msg)) {
                return
            }

            val proxyConnection = ctx.channel().requestResponse()
            proxyConnection.client.writeAndFlush(msg)
        }
    }

    private fun enqueuIfConnecting(
        ctx: ChannelHandlerContext,
        msg: HttpContent
    ): Boolean {
        val queue = ctx.channel().attr(preConnectQueueAttributeKey).get()
        if (queue != null) {
            if (!queue.offer(msg)) {
                throw RuntimeException("pre connect queue full")
            }
            return true
        }
        return false
    }

    private suspend fun drainPreConnectQueue(
        queue: Queue<HttpContent>,
        channel: Channel
    ) {
        var qMsg = queue.poll()
        while (qMsg != null) {
            channel.writeAndFlush(qMsg).wait()
            qMsg = queue.poll()
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        cause.printStackTrace()
        ctx.close()
    }
}