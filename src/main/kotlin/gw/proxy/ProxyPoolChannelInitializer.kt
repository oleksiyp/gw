package gw.proxy

import gw.client.HttpClient
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.channel.pool.AbstractChannelPoolHandler
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpContentDecompressor
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse

class ProxyPoolChannelInitializer(val ssl: Boolean) : AbstractChannelPoolHandler() {

    override fun channelCreated(ch: Channel) {
        val p = ch.pipeline()
        if (ssl) {
            val sslCtx = ch.attr(HttpClient.sslKeyAttribute).get()
            p.addLast(sslCtx.newHandler(ch.alloc()))
        }

        p.addLast(HttpClientCodec())
        p.addLast(HttpContentDecompressor())
        p.addLast(WriteableExceptionHandler())
        p.addLast(ProxyClientHandler())
    }
}
