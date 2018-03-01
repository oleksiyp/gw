package gw

import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.EventLoop
import io.netty.handler.ssl.SslContext

@ChannelHandler.Sharable
class GwClientInitializer(
    val sslCtx: SslContext,
    val clientConfig: GwClient.Config
) : ChannelInboundHandlerAdapter() {
    val clients = HashMap<EventLoop, GwClient>()

    override fun channelRegistered(ctx: ChannelHandlerContext) {
        val channel = ctx.channel()
        val eventLoop = channel.eventLoop()
        val client = synchronized(clients) { getOrCreateClient(eventLoop) }
        channel.attr(GwClient.attributeKey).set(client)
    }

    private fun getOrCreateClient(eventLoop: EventLoop): GwClient? {
        var client = clients[eventLoop]
        if (client == null) {
            client = GwClient(eventLoop, sslCtx, clientConfig)
            clients[eventLoop] = client
        } else {
            client.retain()
        }
        return client
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext) {
        val client = ctx.channel().attr(GwClient.attributeKey).getAndSet(null)
        synchronized(clients) {
            if (client.release()) {
                clients.remove(ctx.channel().eventLoop())
            }
        }
    }
}

