package gw.proxy

import gw.client.HttpClient
import gw.rewrite.ProxyRewriteRules
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.HttpObject
import io.netty.handler.codec.http.HttpRequest
import io.netty.util.AttributeKey
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.experimental.launch
import org.slf4j.LoggerFactory

class ProxyServerHandler(
    private val rewriteRules: ProxyRewriteRules
) : SimpleChannelInboundHandler<HttpObject>() {

    override fun channelInactive(ctx: ChannelHandlerContext) {
        val reqResponse = ctx.requestResponseNullable ?: return
        ctx.requestResponseNullable = null
        launch(reqResponse.dispatcher) {
            reqResponse.closeServer()
        }
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpObject) {
        if (ctx.skip) {
            return
        }

        if (msg is HttpRequest) {
            if (!canHandleNextRequest(ctx)) {
                ctx.skip = true
                ctx.requestResponse.shouldCloseServer = true
                return
            }

            val client = ctx.channel().attr(HttpClient.attributeKey).get()
            val newReqResp = ProxyHttpRequestResponse(ctx.channel(), ctx.alloc())
            ctx.requestResponse = newReqResp

            val results = rewriteRules.rewrite(msg)

            newReqResp.connectClient(msg, client.poolMap, results)
        }

        ReferenceCountUtil.retain(msg)
        ctx.requestResponse.sendClient(msg)
    }

    private fun canHandleNextRequest(
        ctx: ChannelHandlerContext
    ): Boolean {
        val reqResp = ctx.requestResponseNullable ?: return true

        if (reqResp.canHandleNextRequest()) {
            return true
        }

        return false
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        if (ctx.skip) {
            return
        }

        ctx.requestResponse.flowControl()
        super.channelWritabilityChanged(ctx)
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        if (ctx.skip) {
            return
        }

        ctx.requestResponseNullable?.flushClient()
        super.channelReadComplete(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        val requestResponse = ctx.requestResponseNullable
        if (requestResponse == null) {
            log.error("Server-side channel error. Bad state", cause)
        } else {
            requestResponse.serverExceptionHappened(cause)
        }
    }

    companion object {
        val skipAttributeKey = AttributeKey.newInstance<Boolean>("skip")

        var ChannelHandlerContext.skip: Boolean
            get() = channel().attr(skipAttributeKey).get() ?: false
            set(value) = channel().attr(skipAttributeKey).set(value)

        val log = LoggerFactory.getLogger(ProxyServerHandler::class.java)
    }
}
