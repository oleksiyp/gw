package gw.rewrite

import io.netty.handler.codec.http.HttpRequest

interface ProxyRewriteRules {
    fun rewrite(request: HttpRequest): List<ProxyRewriteResult>
}