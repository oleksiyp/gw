package gw

interface GwRewriteRules {
    fun rewrite(uri: String): String
}