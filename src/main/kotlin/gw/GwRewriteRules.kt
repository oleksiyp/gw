package gw

import io.netty.handler.codec.http.HttpRequest

interface GwRewriteRules {
    fun rewrite(request: HttpRequest): GwRewriteResult
}