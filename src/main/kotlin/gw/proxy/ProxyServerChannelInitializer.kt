package gw.proxy

import gw.client.HttpClientInitializer
import gw.rewrite.ProxyRewriteRules
import io.netty.channel.*
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpServerExpectContinueHandler
import io.netty.handler.ssl.SslContext

class ProxyServerChannelInitializer(
    val clientInitializer: HttpClientInitializer,
    val sslCtx: SslContext?,
    val rewriteRules: ProxyRewriteRules
) : ChannelInitializer<SocketChannel>() {

    public override fun initChannel(ch: SocketChannel) {
        val p = ch.pipeline()
        p.addLast(clientInitializer)
        sslCtx?.let { p.addLast(it.newHandler(ch.alloc())) }
        p.addLast(HttpServerCodec())
        p.addLast(HttpServerExpectContinueHandler())
        p.addLast(WriteableExceptionHandler())
        p.addLast(ProxyServerHandler(rewriteRules))
    }

}

