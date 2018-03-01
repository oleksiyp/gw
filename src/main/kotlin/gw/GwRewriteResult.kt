package gw

import java.net.InetSocketAddress
import java.net.URI

class GwRewriteResult(
    val rewrittenUri: String
) {
    val target = URI(rewrittenUri)
    val parser = HttpURLParser(target)
    val targetPort = parser.port
    val targetAddr = InetSocketAddress(parser.host, targetPort)
    val secure = target.scheme.equals("https", ignoreCase = true)
    val poolKey = GwPoolKey(targetAddr, secure)
}