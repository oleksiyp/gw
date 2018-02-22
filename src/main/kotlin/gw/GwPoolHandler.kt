package gw

import io.netty.channel.Channel
import io.netty.channel.pool.AbstractChannelPoolHandler
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpContentDecompressor
import java.util.concurrent.atomic.AtomicInteger

class GwPoolHandler(val ssl: Boolean) : AbstractChannelPoolHandler() {
    val counter = AtomicInteger()
    override fun channelCreated(ch: Channel) {
        val p = ch.pipeline()
        if (ssl) {
            val sslCtx = ch.attr(GwApp.sslKeyAttribute).get()
            p.addLast(sslCtx.newHandler(ch.alloc()))
        }

        p.addLast(HttpClientCodec())
        p.addLast(HttpContentDecompressor())
//        p.addLast(LoggingHandler(LogLevel.INFO))
        p.addLast(GwClientHandler())
        println("Acquired ${counter.incrementAndGet()}")
    }

    override fun channelAcquired(ch: Channel?) {
        println("Acquired ${counter.incrementAndGet()}")
    }

    override fun channelReleased(ch: Channel?) {
        println("Released ${counter.decrementAndGet()}")
    }

}