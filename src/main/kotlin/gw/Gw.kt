package gw

import gw.client.HttpClient
import gw.client.HttpClientInitializer
import gw.proxy.ProxyServerChannelInitializer
import gw.rewrite.ProxyRewriteRules
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.epoll.EpollServerSocketChannel
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.util.concurrent.DefaultThreadFactory
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    val httpsServer = GwApp(GwApp.Config(true, 443), GwRewriteRules(true))
    httpsServer.listen()
    val httpServer = GwApp(GwApp.Config(false, 80), GwRewriteRules(false))
    httpServer.listen()

    Runtime.getRuntime().addShutdownHook(Thread {
        GwApp.log.info("Gracefully shutting down")
        httpsServer.stop()
        httpServer.stop()
    })
}

class GwApp(
    val config: Config,
    val rewriteRules: ProxyRewriteRules
) {
    data class Config(
        val ssl: Boolean,
        val port: Int,
        val serverThreads: Int = 8,
        val clientThreads: Int = 8,
        val preConnectQueueSize: Int = 20,
        val clientConfig: HttpClient.Config = HttpClient.Config()
    )

    private val serverGroup = EpollEventLoopGroup(1, DefaultThreadFactory("selector"))
    private val serverWorkerGroup = EpollEventLoopGroup(config.serverThreads, DefaultThreadFactory("worker"))
    private val sslCtx: SslContext? = configureSSL()
    private val clientSslCtx = configureClientSSL()
    private val clientInitializer = HttpClientInitializer(clientSslCtx, config.clientConfig)
    private val serverBootstrap = createServerBootstrap()

    fun listen(): Channel = serverBootstrap
        .bind(config.port)
        .addListener { log.info("Listening ${config.port} ${if (config.ssl) "https" else "http"}") }
        .sync()
        .channel()

    fun stop() {
        serverGroup.shutdownGracefully()
        serverWorkerGroup.shutdownGracefully()
    }

    private fun configureClientSSL(): SslContext {
        return SslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE).build()
    }


    private fun configureSSL(): SslContext? {
        if (!config.ssl) {
            return null
        }
        val ssc = SelfSignedCertificate()
        return SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build()
    }

    private fun createServerBootstrap(): ServerBootstrap {
        val bootstrap = ServerBootstrap()
        bootstrap.option(ChannelOption.SO_BACKLOG, 1024)
        bootstrap.childOption(ChannelOption.TCP_NODELAY, true)
        bootstrap.group(serverGroup, serverWorkerGroup)
            .channel(EpollServerSocketChannel::class.java)
            .childHandler(ProxyServerChannelInitializer(clientInitializer, sslCtx, rewriteRules))
        return bootstrap
    }

    companion object {
        val log = LoggerFactory.getLogger(GwApp::class.java)
    }
}
