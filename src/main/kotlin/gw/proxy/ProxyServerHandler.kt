package gw.proxy

import gw.client.HttpClient
import gw.rewrite.ProxyRewriteRules
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.*
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.launch

class ProxyServerHandler(
    private val rewriteRules: ProxyRewriteRules
) : ChannelInboundHandlerAdapter() {

    override fun channelActive(ctx: ChannelHandlerContext) {
        val pipeline = HttpPipeline(ctx.channel(), ctx.alloc())

        ctx.channel().attr(HttpPipeline.attributeKey).set(pipeline)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        val pipeline = ctx.channel().attr(HttpPipeline.attributeKey).getAndSet(null)
        launch(ctx.channel().eventLoop().asCoroutineDispatcher()) {
            pipeline.abort()
        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val pipeline = ctx.httpPipelineSeries()
        if (msg is HttpRequest) {
            val client = ctx.channel().attr(HttpClient.attributeKey).get()
            val results = rewriteRules.rewrite(msg)

            val closeServer = if (!HttpUtil.isKeepAlive(msg)) {
                HttpUtil.setKeepAlive(msg, true)
                true
            } else {
                false
            }

            val reqResp = pipeline.pushRequestResponse(closeServer)
            if (results.isEmpty()) {
                throw RuntimeException("empty rewrite result")
            }

            ReferenceCountUtil.retain(msg)

            pipeline.flowControl()
            reqResp.connectClient(msg, client.poolMap, results)

        } else if (msg is HttpObject) {
            ReferenceCountUtil.retain(msg)
            pipeline.sendClient(msg)
        }
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        ctx.httpPipelineSeries().flowControl()
        super.channelWritabilityChanged(ctx)
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        ctx.httpPipelineSeries().flushClient()
        super.channelReadComplete(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.httpPipelineSeries().serverExceptionHappened(cause)
    }
}
