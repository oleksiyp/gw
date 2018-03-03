package gw.error

import io.netty.buffer.ByteBufAllocator
import io.netty.handler.codec.http.*
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.Charset

object BadGatewayResponseBuilder {
    private val utf8 = Charset.forName("UTF-8")

    fun buildErrorResponse(
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

}