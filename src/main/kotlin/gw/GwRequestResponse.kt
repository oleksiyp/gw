package gw

import gw.GwRequestReponseState.Flag.*
import io.netty.buffer.ByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.pool.ChannelPool
import io.netty.channel.pool.ChannelPoolMap
import io.netty.handler.codec.http.*
import io.netty.util.AttributeKey
import kotlinx.coroutines.experimental.launch
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GwRequestResponse(
    private val serverCtx: ChannelHandlerContext,
    preConnectQueueSize: Int
) {
    private lateinit var pool: ChannelPool
    private val server = serverCtx.channel()
    private lateinit var client: Channel
    private val state = AtomicInteger()
    private val preConnectQueue = AtomicReference<Queue<HttpContent>>(
        LinkedBlockingQueue<HttpContent>(preConnectQueueSize)
    )

    @Volatile
    private var shouldCloseClient = false

    @Volatile
    private var shouldCloseServer = false

    companion object {
        val attributeKey = AttributeKey.newInstance<GwRequestResponse>("requestResponse")
        val utf8 = Charset.forName("UTF-8")
    }


    fun handleConnectionClose(headers: HttpHeaders, client: Boolean) {
        if (headers.contains(
                HttpHeaderNames.CONNECTION,
                HttpHeaderValues.CLOSE,
                true
            )
        ) {
            headers.remove(HttpHeaderNames.CONNECTION)

            if (client) {
                shouldCloseClient = true
            } else {
                shouldCloseServer = true
            }
        }
    }

    fun sendDownstream(msg: HttpObject) = client.writeAndFlush(msg)
    fun sendUpstream(msg: HttpObject): ChannelFuture {
        if (msg is HttpResponse) {
            println("$requestInfo ${msg.headers().get(HttpHeaderNames.CONTENT_TYPE)} ${msg.status()}")
            switchState(RESPONSE_SENT)
        }
        return server.writeAndFlush(msg)
    }

    private lateinit var requestInfo: String

    fun connectClient(
        request: HttpRequest,
        rewriteRules: GwRewriteRules,
        poolMap: ChannelPoolMap<GwPoolKey, ChannelPool>
    ) {
        val rewriteResult = rewriteRules.rewrite(request)
        requestInfo = "${request.protocolVersion()} ${request.method()} ${rewriteResult.target.path}"
        pool = poolMap[rewriteResult.poolKey]
        server.config().isAutoRead = false
        val self = this
        launch {
            try {
                client = pool.acquire().wait()

//                request.headers().set("Host", rewriteResult.targetHost)

                client.attr(GwRequestResponse.attributeKey).set(self)

                handleConnectionClose(request.headers(), false)

                client.writeAndFlush(request)
                    .wait()

                drainPreConnectQueue()

                server.config().isAutoRead = true

                switchToInitialziedState()
            } catch (e: Throwable) {
                server.write(e)
            }
        }

    }

    private fun switchToInitialziedState() {
        val wasState = getAndUpdateState {
            if (contains(CLIENT_CLOSED) || contains(CLIENT_RELEASED_TO_POOL)) {
                this
            } else {
                on(CLIENT_INTIALIZED)
            }
        }
        if (CLIENT_CLOSED in wasState) {
            client.close()
        }
        if (CLIENT_RELEASED_TO_POOL in wasState) {
            pool.release(client)
        }
    }

    fun enqueueIfConnecting(msg: HttpContent): Boolean {
        val queue = preConnectQueue.get()
                ?: return false

        if (!queue.offer(msg)) {
            throw RuntimeException("pre connect queue full")
        }

        return true
    }

    private suspend fun drainPreConnectQueue() {
        val queue = preConnectQueue.getAndSet(null)
        var qMsg = queue.poll()
        while (qMsg != null) {
            sendDownstream(qMsg).wait()
            qMsg = queue.poll()
        }
    }

    fun exceptionHappened(cause: Throwable, clientSide: Boolean) {
        if (clientSide) {
            server.write(cause)
            done(true)
            return
        }

        if (!switchState(RESPONSE_SENT)) {
            serverClose()
            done(true)
            return
        }

        val respose = errorResponse(serverCtx.alloc(), cause)
        launch {
            server.writeAndFlush(respose).wait()
            serverClose()
        }

        if (state().contains(CLIENT_INTIALIZED)) {
            client.write(cause)
        }
    }


    private fun errorResponse(
        alloc: ByteBufAllocator,
        cause: Throwable
    ): HttpResponse {
        val buf = alloc.buffer()
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println(
            """
            |<html>
            |<head>
            |    <title>gw 502</title>
            |</head>
            |<body>
            |    <h2>gw 502 Bad Gateway: ${cause.message}</h2>
            |<pre>""".trimMargin()
        )
        pw.println(stackTrace(cause.stackTrace))
        var nextCause = cause.cause
        var prevCause: Throwable? = cause
        while (nextCause != null && prevCause != nextCause) {
            pw.println("<h3>${nextCause.message}</h3>")
            pw.println(stackTrace(nextCause.stackTrace))
            prevCause = nextCause
            nextCause = nextCause.cause
        }

        buf.writeCharSequence(sw.toString(), utf8)

        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.BAD_GATEWAY,
            buf
        )

        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        response.headers().set(
            HttpHeaderNames.CONTENT_LENGTH,
            buf.writerIndex() - buf.readerIndex()
        )
        response.headers().set(
            HttpHeaderNames.CONTENT_TYPE,
            "text/html; charset=utf-8"
        )
        return response
    }

    fun stackTrace(stackTrace: Array<StackTraceElement>): String {
        fun columnSize(block: StackTraceElement.() -> String) =
            stackTrace.map(block).map { it.length }.max() ?: 0

        fun StackTraceElement.fileLine() =
            "($fileName:$lineNumber)${if (isNativeMethod) "N" else ""}"

        fun spaces(n: Int) = if (n < 0) "" else (1..n).map { " " }.joinToString("")
        fun columnRight(s: String, sz: Int) = spaces(sz - s.length) + s
        fun columnLeft(s: String, sz: Int) = s + spaces(sz - s.length)

        fun String.pkg(): String {
            val idx = lastIndexOf('.')
            return substring(0, if (idx == -1) 0 else idx)
        }

        fun String.cls(): String {
            val idx = lastIndexOf('.')
            return substring(if (idx == -1) 0 else 1 + idx)
        }

        val maxPkgNameLen = columnSize { className.pkg() }
        val maxClassNameLen = columnSize { className.cls() }
        val maxMethodLen = columnSize { methodName }
        val maxThirdColumn = columnSize { fileLine() }

        return stackTrace.map {
            columnLeft(it.className.pkg(), maxPkgNameLen) + " " +
                    columnRight(it.className.cls(), maxClassNameLen) + "." +
                    columnLeft(it.methodName, maxMethodLen) + " " +
                    columnLeft(it.fileLine(), maxThirdColumn)
        }.joinToString("\n")
    }


    private fun state() = GwRequestReponseState(state.get())
    private fun getAndUpdateState(
        transition: GwRequestReponseState.() -> GwRequestReponseState
    ): GwRequestReponseState {
        var prev: GwRequestReponseState
        var next: GwRequestReponseState
        do {
            prev = GwRequestReponseState(state.get())
            next = transition(prev)
            if (prev == next) {
                break
            }
        } while (!state.compareAndSet(prev.flags, next.flags))
        return prev
    }

    private fun switchState(flag: GwRequestReponseState.Flag) = !getAndUpdateState(flag).contains(flag)

    private fun getAndUpdateState(flag: GwRequestReponseState.Flag) =
        getAndUpdateState { on(flag) }

    fun done(close: Boolean = false) {
        if (shouldCloseClient or close) {
            clientClose().sync()
        }

        if (switchState(CLIENT_RELEASED_TO_POOL) && state().contains(CLIENT_INTIALIZED)) {
            pool.release(client)
        }
    }

    private fun serverClose(): ChannelFuture {
        if (switchState(SERVER_CLOSED)) {
            return server.close()
        } else {
            return server.newSucceededFuture()
        }
    }

    private fun clientClose(): ChannelFuture {
        if (switchState(CLIENT_CLOSED) && state().contains(CLIENT_INTIALIZED)) {
            return client.close()
        } else {
            return client.newSucceededFuture()
        }
    }

}

