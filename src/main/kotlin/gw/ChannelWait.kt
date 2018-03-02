package gw

import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.util.concurrent.Future
import kotlinx.coroutines.experimental.cancelFutureOnCompletion
import kotlinx.coroutines.experimental.suspendCancellableCoroutine


suspend fun Future<Channel>.wait(): Channel =
    suspendCancellableCoroutine { cont ->
        val future = this
        cont.cancelFutureOnCompletion(future)
        future.addListener {
            if (future.isSuccess) {
                cont.resume(future.get())
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

