package gw.client

import java.net.InetSocketAddress

data class HttpClientPoolKey(
    val address: InetSocketAddress,
    val ssl: Boolean
)