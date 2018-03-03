package gw.proxy

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.HttpObject
import io.netty.util.ReferenceCountUtil

class ProxyClientHandler : SimpleChannelInboundHandler<HttpObject>() {
    public override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpObject) {
        ReferenceCountUtil.retain(msg)
        ctx.requestResponse.sendServer(msg)
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        ctx.requestResponse.flowControl()
        super.channelWritabilityChanged(ctx)
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        ctx.requestResponseNullable?.flushServer()
        super.channelReadComplete(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        val requestResponse = ctx.requestResponseNullable
        if (requestResponse == null) {
            ProxyServerHandler.log.error("Client-side channel error. Bad state", cause)
        } else {
            requestResponse.clientExceptionHappened(cause)
        }
    }
}
