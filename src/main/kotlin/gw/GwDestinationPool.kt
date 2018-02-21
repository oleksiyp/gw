package gw

import io.netty.bootstrap.Bootstrap
import io.netty.channel.pool.ChannelHealthChecker
import io.netty.channel.pool.FixedChannelPool
import java.net.InetSocketAddress

class GwDestinationPool(
    bootstrap: Bootstrap,
    maxConnections: Int,
    val address: InetSocketAddress,
    val ssl: Boolean
) :
    FixedChannelPool(
        bootstrap,
        GwPoolHandler(ssl),
        maxConnections
    ) {

    override fun connectChannel(bs: Bootstrap) = bs.connect(address)
}