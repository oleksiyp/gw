package gw

import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.pool.AbstractChannelPoolMap
import io.netty.channel.pool.ChannelPool
import io.netty.channel.pool.SimpleChannelPool
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.util.AttributeKey

fun main(args: Array<String>) {
    val ssl = System.getProperty("ssl") != null
    val port = Integer.parseInt(
        System.getProperty(
            "port",
            if (ssl) "8443" else "8080"
        )
    )

    val config = GwApp.Config(ssl, port)
    val server = GwApp(config)
    server.listen()
}

class GwApp(
    val config: Config
) {
    data class Config(
        val ssl: Boolean,
        val port: Int,
        val serverThreads: Int = 8,
        val clientThreads: Int = 8,
        val connectionsPerDestintation: Int = 6,
        val preConnectQueueSize: Int = 8
    )

    private val serverGroup = NioEventLoopGroup(1)
    private val serverWorkerGroup = NioEventLoopGroup(config.serverThreads)
    private val sslCtx: SslContext? = configureSSL()
    private val serverBootstrap = createServerBootstrap()
    private val clientGroup = NioEventLoopGroup(config.clientThreads)
    private val clientBootstrap: Bootstrap = createClientBootstrap()
    private val poolMap = createPoolMap()

    private inner class RewriteRules: GwRewriteRules {
        override fun rewrite(uri: String) =
            when {
                uri.startsWith("/github") -> "https://github.com" + uri.substring("/github".length)
                else -> throw RuntimeException("no rewrite rule")
            }
    }

    fun listen() = serverBootstrap
        .bind(config.port)
        .sync()
        .channel()


    fun stop() {
        serverGroup.shutdownGracefully()
        serverWorkerGroup.shutdownGracefully()
        clientGroup.shutdownGracefully()
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

    private fun createClientBootstrap(): Bootstrap {
        val sslCtx = SslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE).build()

        val bootstrap = Bootstrap()
        bootstrap.attr(sslKeyAttribute, sslCtx)

        bootstrap
            .group(clientGroup)
            .channel(NioSocketChannel::class.java)

        return bootstrap
    }

    private fun configureSSL(): SslContext? {
        return if (config.ssl) {
            val ssc = SelfSignedCertificate()
            SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build()
        } else {
            null
        }
    }


    private fun createServerBootstrap(): ServerBootstrap {
        val bootstrap = ServerBootstrap()
        bootstrap.option(ChannelOption.SO_BACKLOG, 1024)
        bootstrap.group(serverGroup, serverWorkerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(ServerInitializer())
        return bootstrap
    }

    private inner class ServerInitializer() : ChannelInitializer<SocketChannel>() {
        public override fun initChannel(ch: SocketChannel) {
            val p = ch.pipeline()
            sslCtx?.let { p.addLast(it.newHandler(ch.alloc())) }
            p.addLast(HttpServerCodec())
            p.addLast(HttpServerExpectContinueHandler())
            p.addLast(LoggingHandler(LogLevel.INFO))
            p.addLast(GwServerHandler(poolMap,
                RewriteRules(),
                config.preConnectQueueSize))
        }
    }

    companion object {
        val sslKeyAttribute = AttributeKey.newInstance<SslContext>("sslContext")
    }
}
