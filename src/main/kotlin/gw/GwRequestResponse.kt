package gw

import io.netty.channel.Channel
import io.netty.channel.pool.ChannelPool
import io.netty.util.AttributeKey

class GwRequestResponse(
    val server: Channel,
    val client: Channel,
    val pool: ChannelPool
) {
    companion object {
        val attributeKey = AttributeKey.newInstance<GwRequestResponse>("requestResponse")
    }
}