package gw

import io.netty.channel.Channel
import io.netty.channel.pool.AbstractChannelPoolHandler
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpContentDecompressor
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler

class GwPoolHandler(val ssl: Boolean) : AbstractChannelPoolHandler() {
    override fun channelCreated(ch: Channel) {
        val p = ch.pipeline()
        if (ssl) {
            val sslCtx = ch.attr(GwApp.sslKeyAttribute).get()
            p.addLast(sslCtx.newHandler(ch.alloc()))
        }

        p.addLast(HttpClientCodec())
        p.addLast(HttpContentDecompressor())
        p.addLast(LoggingHandler(LogLevel.INFO))
        p.addLast(GwClientHandler())
    }
}