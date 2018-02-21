package gw

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.*
import io.netty.util.ReferenceCountUtil

class GwClientHandler : SimpleChannelInboundHandler<HttpObject>() {
    public override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpObject) {
        ReferenceCountUtil.retain(msg)
        val reqResp = ctx.channel().requestResponse()
        reqResp.server.writeAndFlush(msg)
        if (msg is LastHttpContent) {
            reqResp.pool.release(ctx.channel())
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        cause.printStackTrace()
        ctx.close()
    }
}
