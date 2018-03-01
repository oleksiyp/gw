package gw

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpServerExpectContinueHandler
import io.netty.handler.flow.FlowControlHandler
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.ssl.util.SelfSignedCertificate
import org.slf4j.LoggerFactory
import org.xbill.DNS.ARecord
import org.xbill.DNS.Lookup
import org.xbill.DNS.SimpleResolver

private class RewriteRules : GwRewriteRules {
    var resolver = SimpleResolver("8.8.8.8")

    override fun rewrite(request: HttpRequest): List<GwRewriteResult> {
        val uri = request.uri()
        val host = request.headers().get("Host")
        val lookup = Lookup(host)
        lookup.setResolver(resolver)
        lookup.run()
        val record = lookup.answers.firstOrNull() as ARecord?
                ?: throw RuntimeException("Failed to resolve $host")
        val ip = record.address.hostAddress
        return listOf(GwRewriteResult("https://" + ip + uri))
    }
}

fun main(args: Array<String>) {
    val ssl = System.getProperty("ssl") != null
    val port = Integer.parseInt(
        System.getProperty(
            "port",
            if (ssl) "8443" else "8080"
        )
    )

    val config = GwApp.Config(ssl, port)
    val server = GwApp(config, RewriteRules())
    server.listen()

    Runtime.getRuntime().addShutdownHook(Thread {
        GwApp.log.info("Gracefully shutting down")
        server.stop()
    })
}

class GwApp(
    val config: Config,
    val rewriteRules: GwRewriteRules
) {
    data class Config(
        val ssl: Boolean,
        val port: Int,
        val serverThreads: Int = 8,
        val clientThreads: Int = 8,
        val preConnectQueueSize: Int = 20,
        val clientConfig: GwClient.Config = GwClient.Config()
    )

    private val serverGroup = NioEventLoopGroup(1)
    private val serverWorkerGroup = NioEventLoopGroup(config.serverThreads)
    private val sslCtx: SslContext? = configureSSL()
    private val clientSslCtx = configureClientSSL()
    private val serverBootstrap = createServerBootstrap()


    fun listen() = serverBootstrap
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
        return SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
            .build()
    }

    private fun createServerBootstrap(): ServerBootstrap {
        val bootstrap = ServerBootstrap()
        bootstrap.option(ChannelOption.SO_BACKLOG, 1024)
        bootstrap.childOption(ChannelOption.TCP_NODELAY, true)
        bootstrap.group(serverGroup, serverWorkerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(ServerInitializer())
        return bootstrap
    }

    private inner class ServerInitializer() : ChannelInitializer<SocketChannel>() {
        val clientInitializer = GwClientInitializer(clientSslCtx, config.clientConfig)

        public override fun initChannel(ch: SocketChannel) {
            val p = ch.pipeline()
            p.addLast(clientInitializer)
            sslCtx?.let { p.addLast(it.newHandler(ch.alloc())) }
            p.addLast(HttpServerCodec())
            p.addLast(HttpServerExpectContinueHandler())
            p.addLast(WriteableExceptionHandler())
            p.addLast(
                GwServerHandler(
                    rewriteRules
                )
            )
        }

        inner class WriteableExceptionHandler : ChannelOutboundHandlerAdapter() {
            override fun write(ctx: ChannelHandlerContext, msg: Any?, promise: ChannelPromise) {
                if (msg is Throwable) {
                    ctx.fireExceptionCaught(msg)
                    promise.setSuccess(null)
                } else {
                    super.write(ctx, msg, promise)
                }
            }
        }
    }

    companion object {
        val log = LoggerFactory.getLogger(GwApp::class.java)
    }
}
