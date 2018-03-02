package gw

import gw.rewrite.ProxyRewriteResult
import gw.rewrite.ProxyRewriteRules
import io.netty.handler.codec.http.HttpRequest
import org.xbill.DNS.ARecord
import org.xbill.DNS.Lookup
import org.xbill.DNS.SimpleResolver

class GwRewriteRules : ProxyRewriteRules {
    var resolver = SimpleResolver("8.8.8.8")

    override fun rewrite(request: HttpRequest): List<ProxyRewriteResult> {
        val uri = request.uri()
        val host = request.headers().get("Host")
        val lookup = Lookup(host)
        lookup.setResolver(resolver)
        lookup.run()
        val record = lookup.answers.firstOrNull() as ARecord?
                ?: throw RuntimeException("Failed to resolve $host")
        val ip = record.address.hostAddress
        return listOf(ProxyRewriteResult("https://" + ip + uri))
    }
}