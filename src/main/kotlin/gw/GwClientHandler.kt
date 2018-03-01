package gw

import gw.GwRequestResponse.Direction.DOWNSTREAM
import gw.GwRequestResponse.Direction.UPSTREAM
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.HttpObject
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.LastHttpContent
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.launch

class GwClientHandler : SimpleChannelInboundHandler<HttpObject>() {
    public override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpObject) {
        ReferenceCountUtil.retain(msg)
        val reqResp = ctx.channel().requestResponse()
                ?: throw RuntimeException("initialization error")

        if (msg is HttpResponse) {
            reqResp.handleConnectionClose(msg.headers(), DOWNSTREAM)
        }

        val writeMsgFuture = reqResp.send(msg, UPSTREAM)
        if (msg is LastHttpContent) {
            launch(reqResp.dispatcher) {
                writeMsgFuture.wait()
                reqResp.done()
            }
        }
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        ctx.channel().requestResponse()?.flowControl()
        super.channelWritabilityChanged(ctx)
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        ctx.channel().requestResponse()?.flush(UPSTREAM)
        super.channelReadComplete(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        val reqResp = ctx.channel().requestResponse()
        if (reqResp == null) {
            ctx.close()
            return
        }
        reqResp.exceptionHappened(cause, DOWNSTREAM)
    }
}
