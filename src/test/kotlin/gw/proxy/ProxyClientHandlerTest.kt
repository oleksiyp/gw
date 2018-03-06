package gw.proxy

import io.mockk.*
import io.mockk.impl.annotations.AdditionalInterface
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.HttpObject
import io.netty.util.ReferenceCounted
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class ProxyClientHandlerTest {
    val clientHandler = ProxyClientHandler()

    @Test
    fun channelRead0(
        @MockK ctx: ChannelHandlerContext,
        @MockK @AdditionalInterface(ReferenceCounted::class) msg: HttpObject
    ) {
        every {
            ctx.requestResponse.sendServer(msg)
        } returns mockk()

        every { (msg as ReferenceCounted).retain() } returns msg as ReferenceCounted

        clientHandler.channelRead0(ctx, msg)

        verify {
            ctx.requestResponse.sendServer(msg)
            (msg as ReferenceCounted).retain()
        }
    }

    @Test
    fun channelWritabilityChanged(
        @MockK ctx: ChannelHandlerContext
    ) {
        every { ctx.requestResponse.flowControl() } just Runs
        every { ctx.fireChannelWritabilityChanged() } returns ctx

        clientHandler.channelWritabilityChanged(ctx)

        verify {
            ctx.requestResponse.flowControl()
            ctx.fireChannelWritabilityChanged()
        }
    }

    @Test
    fun channelReadComplete(
        @MockK ctx: ChannelHandlerContext
    ) {
        every { ctx.requestResponse.flushServer() } just Runs
        every { ctx.fireChannelReadComplete() } returns ctx

        clientHandler.channelReadComplete(ctx)

        verify {
            ctx.requestResponse.flushServer()
            ctx.fireChannelReadComplete()
        }
    }


    @Test
    fun exceptionCaughtNull(
        @MockK ctx: ChannelHandlerContext
    ) {
        every { ctx.requestResponseNullable } returns null

        objectMockk(ProxyServerHandler.log).use {
            every { ProxyServerHandler.log.error(any(), any<Throwable>()) } just Runs

            clientHandler.exceptionCaught(ctx, RuntimeException("error"))

            verify {
                ProxyServerHandler.log.error("Client-side channel error. Bad state", ofType(RuntimeException::class))
            }

        }
    }

    @Test
    fun exceptionCaught(
        @MockK ctx: ChannelHandlerContext
    ) {
        every { ctx.requestResponse.clientExceptionHappened(any()) } just Runs

        clientHandler.exceptionCaught(ctx, RuntimeException("error"))

        verify {
            ctx.requestResponse.clientExceptionHappened(ofType(RuntimeException::class))
        }
    }
}