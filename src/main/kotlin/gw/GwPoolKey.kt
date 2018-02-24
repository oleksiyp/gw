package gw

import java.net.InetSocketAddress

data class GwPoolKey(
    val address: InetSocketAddress,
    val ssl: Boolean
)