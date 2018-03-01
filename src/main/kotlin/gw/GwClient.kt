package gw

import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoop
import io.netty.channel.pool.AbstractChannelPoolMap
import io.netty.channel.pool.ChannelPool
import io.netty.channel.pool.SimpleChannelPool
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.ssl.SslContext
import io.netty.util.AbstractReferenceCounted
import io.netty.util.AttributeKey
import io.netty.util.ReferenceCounted
import org.slf4j.LoggerFactory

class GwClient(
    private val eventLoop: EventLoop,
    private val sslCtx: SslContext,
    private val config: Config
) : AbstractReferenceCounted() {

    data class Config(
        val connectionsPerDestintation: Int = 2
    )

    private val clientBootstrap: Bootstrap = createClientBootstrap()
    val poolMap = createPoolMap()

    private fun createClientBootstrap(): Bootstrap {
        log.info("Creating client bootstrap")
        val bootstrap = Bootstrap()
        bootstrap.attr(sslKeyAttribute, sslCtx)
        bootstrap.option(ChannelOption.TCP_NODELAY, true)

        bootstrap
            .group(eventLoop)
            .channel(NioSocketChannel::class.java)

        return bootstrap
    }

    private fun createPoolMap(): AbstractChannelPoolMap<GwPoolKey, ChannelPool> {
        return object : AbstractChannelPoolMap<GwPoolKey, ChannelPool>() {
            override fun newPool(gwPoolKey: GwPoolKey): SimpleChannelPool {
                return GwDestinationPool(
                    clientBootstrap,
                    config.connectionsPerDestintation,
                    gwPoolKey.address,
                    gwPoolKey.ssl
                )
            }
        }
    }

    override fun touch(hint: Any) = this

    override fun deallocate() {
        log.info("Shutting down client pool")
        poolMap.forEach { it.value.close() }
    }

    companion object {
        val attributeKey = AttributeKey.newInstance<GwClient>("client")
        val sslKeyAttribute = AttributeKey.newInstance<SslContext>("clientSslContext")
        val log = LoggerFactory.getLogger(GwClientInitializer::class.java)
    }

}