package gw

import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.handler.codec.http.*
import io.netty.util.CharsetUtil
import io.netty.util.concurrent.Future
import kotlinx.coroutines.experimental.cancelFutureOnCompletion
import kotlinx.coroutines.experimental.suspendCancellableCoroutine
import java.net.URI

class HttpURLParser(val uri: URI) {
    val scheme = if (uri.scheme == null) "http" else uri.scheme
    val host = if (uri.host == null) "127.0.0.1" else uri.host
    var port = if (uri.port == -1) {
        if (schemaIs("http")) {
            80
        } else if (schemaIs("https")) {
            443
        } else {
            throw RuntimeException("Only HTTP(S) is supported.")
        }
    } else {
        uri.port
    }

    init {
        if (!schemaIs("http") && !schemaIs("https")) {
            throw RuntimeException("Only HTTP(S) is supported.")
        }
    }

    private fun schemaIs(value: String) = value.equals(scheme, ignoreCase = true)
}


fun Channel.requestResponse(): GwRequestResponse = attr(GwRequestResponse.attributeKey).get()

suspend fun Future<Channel>.wait(): Channel =
    suspendCancellableCoroutine { cont ->
        val future = this
        cont.cancelFutureOnCompletion(future)
        future.addListener {
            val channel = future.get()
            if (future.isSuccess) {
                cont.resume(channel)
            } else {
                cont.resumeWithException(future.cause())
            }
        }
    }

suspend fun ChannelFuture.wait(): Channel =
    suspendCancellableCoroutine { cont ->
        val future = this
        cont.cancelFutureOnCompletion(future)
        future.addListener {
            val channel = future.channel()
            if (future.isSuccess) {
                cont.resume(channel)
            } else {
                cont.resumeWithException(future.cause())
            }
        }
    }

