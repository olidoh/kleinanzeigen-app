package de.olidoh.inserathelfer.ebay

import android.webkit.CookieManager

/**
 * Löscht nur die eBay-Cookies im WebView. Die Kleinanzeigen-Anmeldung liegt im selben Cookie-Speicher
 * und bleibt erhalten. Android kann Cookies nicht je Domain löschen, deshalb werden sie einzeln abgelaufen gesetzt.
 */
object EbayCookies {

    private val URLS = listOf(
        "https://www.ebay.de", "https://signin.ebay.de", "https://ebay.de",
        "https://www.ebay.com", "https://signin.ebay.com", "https://auth.ebay.com", "https://ebay.com",
        "https://auth.sandbox.ebay.com", "https://signin.sandbox.ebay.com", "https://sandbox.ebay.com",
    )

    fun clear() {
        val manager = CookieManager.getInstance()
        URLS.forEach { url ->
            val host = url.removePrefix("https://")
            val parent = "." + host.split('.').takeLast(2).joinToString(".")
            manager.getCookie(url)?.split(';')?.map { it.substringBefore('=').trim() }?.filter(String::isNotEmpty)?.forEach { name ->
                val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
                manager.setCookie(url, expired)
                manager.setCookie(url, "$expired; Domain=$parent")
                manager.setCookie(url, "$expired; Domain=.$host")
            }
        }
        manager.flush()
    }
}
