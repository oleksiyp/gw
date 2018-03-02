package gw.proxy

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise

class WriteableExceptionHandler : ChannelOutboundHandlerAdapter() {
    override fun write(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise) {
        if (msg is Throwable) {
            ctx.fireExceptionCaught(msg)
            promise.setSuccess(null)
        } else {
            super.write(ctx, msg, promise)
        }
    }
}