package gw.client

import io.netty.bootstrap.Bootstrap
import io.netty.channel.pool.ChannelHealthChecker
import io.netty.channel.pool.ChannelPoolHandler
import io.netty.channel.pool.FixedChannelPool
import java.net.InetSocketAddress

class HttpClientPool(
    bootstrap: Bootstrap,
    maxConnections: Int,
    val address: InetSocketAddress,
    poolHandler: ChannelPoolHandler
) :
    FixedChannelPool(
        bootstrap,
        poolHandler,
        ChannelHealthChecker.ACTIVE,
        AcquireTimeoutAction.FAIL,
        1000,
        maxConnections,
        2,
        true,
        true
    ) {

    override fun connectChannel(bs: Bootstrap) = bs.connect(address)
}