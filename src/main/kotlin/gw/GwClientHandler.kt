package gw

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.*
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.launch

class GwClientHandler : SimpleChannelInboundHandler<HttpObject>() {
    public override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpObject) {
        ReferenceCountUtil.retain(msg)
        val reqResp = ctx.channel().requestResponse()
                ?: throw RuntimeException("initialization error")

        if (msg is HttpResponse) {
            reqResp.handleConnectionClose(msg.headers(), true)
        }

        val writeMsgFuture = reqResp.sendUpstream(msg)
        launch {
            writeMsgFuture.wait()
            if (msg is LastHttpContent) {
                reqResp.done()
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        val reqResp = ctx.channel().requestResponse()
        if (reqResp == null) {
            ctx.close()
            return
        }
        reqResp.exceptionHappened(cause, true)
    }


}
