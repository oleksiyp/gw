package gw

import gw.GwRequestResponse.Direction.*
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.pool.ChannelPool
import io.netty.channel.pool.ChannelPoolMap
import io.netty.handler.codec.http.*
import io.netty.util.ReferenceCountUtil

class GwServerHandler(
    private val poolMap: ChannelPoolMap<GwPoolKey, ChannelPool>,
    private val rewriteRules: GwRewriteRules,
    val preConnectQueueSize: Int
) : ChannelInboundHandlerAdapter() {

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is HttpRequest) {
            ReferenceCountUtil.retain(msg)

            val reqResp = GwRequestResponse(ctx, preConnectQueueSize)
            ctx.channel().attr(GwRequestResponse.attributeKey).set(reqResp)
            reqResp.connectClient(msg, rewriteRules, poolMap)
        }
        if (msg is HttpContent) {
            ReferenceCountUtil.retain(msg)

            val reqResp = ctx.channel().requestResponse()
                    ?: throw RuntimeException("bad initialization")

            if (reqResp.enqueueIfConnecting(msg)) {
                return
            }

            reqResp.sendDownstream(msg)
        }
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        val reqResp = ctx.channel().requestResponse()
        if (reqResp != null) {
            reqResp.setAutoRead(ctx.channel().isWritable, DOWNSTREAM)
        }

        super.channelWritabilityChanged(ctx)
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        val reqResp = ctx.channel().requestResponse()
        if (reqResp != null) {
            reqResp.flushDownstream()
        }
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

