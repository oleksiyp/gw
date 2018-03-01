package gw

import gw.GwRequestResponse.Direction.*
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.*
import io.netty.util.ReferenceCountUtil

class GwServerHandler(
    private val rewriteRules: GwRewriteRules
) : ChannelInboundHandlerAdapter() {

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is HttpRequest) {
            ReferenceCountUtil.retain(msg)

            val client = ctx.channel().attr(GwClient.attributeKey).get()
            val results = rewriteRules.rewrite(msg)

            if (results.isEmpty()) {
                throw RuntimeException("empty rewrite result")
            }

            val reqResp = GwRequestResponse(ctx)
            ctx.channel().attr(GwRequestResponse.attributeKey).set(reqResp)

            ctx.channel().config().isAutoRead = false

            reqResp.connectClient(msg, client.poolMap, results)
        } else if (msg is HttpObject) {
            ReferenceCountUtil.retain(msg)

            val reqResp = ctx.channel().requestResponse()
                    ?: throw RuntimeException("bad initialization")

            reqResp.send(msg, DOWNSTREAM)
        }
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        ctx.channel().requestResponse()?.flowControl()

        super.channelWritabilityChanged(ctx)
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        ctx.channel().requestResponse()?.flush(DOWNSTREAM)

        super.channelReadComplete(ctx)
    }


    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        val reqResp = ctx.channel().requestResponse()
        if (reqResp == null) {
            ctx.close()
            return
        }
        reqResp.exceptionHappened(cause, UPSTREAM)
    }
}

