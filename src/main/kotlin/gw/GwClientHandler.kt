package gw

import gw.GwRequestResponse.Direction.*
import io.netty.buffer.PooledByteBufAllocator
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
            reqResp.handleConnectionClose(msg.headers(), DOWNSTREAM)
        }

        val writeMsgFuture = reqResp.sendUpstream(msg)
        if (msg is LastHttpContent) {
            launch {
                writeMsgFuture.wait()
                reqResp.done()
            }
        }
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        val reqResp = ctx.channel().requestResponse()
        if (reqResp != null) {
            reqResp.setAutoRead(ctx.channel().isWritable, UPSTREAM);
        }
        super.channelWritabilityChanged(ctx)
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        val reqResp = ctx.channel().requestResponse()
        if (reqResp != null) {
            reqResp.flushUpstream()
        }
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
