package gw.rewrite

import gw.client.HttpClientPoolKey
import java.net.InetSocketAddress
import java.net.URI

class ProxyRewriteResult(
    val rewrittenUri: String
) {
    val target = URI(rewrittenUri)
    val parser = HttpURLParser(target)
    val targetPort = parser.port
    val targetAddr = InetSocketAddress(parser.host, targetPort)
    val secure = target.scheme.equals("https", ignoreCase = true)
    val poolKey = HttpClientPoolKey(targetAddr, secure)

    override fun toString() = rewrittenUri
}