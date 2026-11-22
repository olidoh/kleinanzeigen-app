package de.olidoh.inserathelfer.core.ebay

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Fehler bei eBay, mit einer Meldung, die man dem Nutzer zeigen kann. */
open class EbayException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Feste, nicht geheime Daten der eBay-Anwendung. Das Client Secret steht hier bewusst nicht:
 * Es liegt nur im Token-Dienst ([tokenServiceUrl]), weil eBay es beim Tausch des Anmeldecodes verlangt
 * und eine App es nicht sicher verstecken kann.
 */
data class EbayConfig(
    val clientId: String,
    /** „RuName“ aus dem eBay-Entwicklerportal; eBay erwartet ihn als `redirect_uri`. */
    val ruName: String,
    val tokenServiceUrl: String,
    val sandbox: Boolean = false,
) {
    val isComplete: Boolean
        get() = clientId.isNotBlank() && ruName.isNotBlank() && tokenServiceUrl.trim().startsWith("https://")

    val authHost: String get() = if (sandbox) "https://auth.sandbox.ebay.com" else "https://auth.ebay.com"
    val apiHost: String get() = if (sandbox) "https://api.sandbox.ebay.com" else "https://api.ebay.com"
    val mediaHost: String get() = if (sandbox) "https://apim.sandbox.ebay.com" else "https://apim.ebay.com"
    val identityHost: String get() = if (sandbox) "https://apiz.sandbox.ebay.com" else "https://apiz.ebay.com"

    companion object {
        /** Rechte, die die App beim Nutzer anfragt: Grunddaten, Angebote anlegen, Versand-/Zahlungsgrundsätze lesen. */
        val SCOPES = listOf(
            "https://api.ebay.com/oauth/api_scope",
            "https://api.ebay.com/oauth/api_scope/sell.inventory",
            "https://api.ebay.com/oauth/api_scope/sell.account",
            // Nur um anzuzeigen, mit welchem eBay-Konto man angemeldet ist.
            "https://api.ebay.com/oauth/api_scope/commerce.identity.readonly",
        )
    }
}

/** Zugang eines eBay-Nutzers. Ablaufzeiten in Millisekunden seit 1970. */
data class EbayTokens(
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
) {
    fun accessValid(now: Long) = accessToken.isNotBlank() && now < accessExpiresAt - 60_000
    fun refreshValid(now: Long) = refreshToken.isNotBlank() && now < refreshExpiresAt
}

/** Ergebnis, wenn eBay nach der Anmeldung auf die hinterlegte Adresse weiterleitet. */
sealed interface EbayRedirect {
    data class Code(val code: String) : EbayRedirect
    data class Denied(val reason: String) : EbayRedirect
}

/**
 * Anmeldung bei eBay per OAuth („Authorization Code Grant“).
 *
 * 1. [authorizeUrl] im WebView öffnen, der Nutzer meldet sich bei eBay an und stimmt zu.
 * 2. eBay leitet auf die „Auth Accepted URL“ weiter; [parseRedirect] liest den Code heraus.
 * 3. [exchangeCode] schickt den Code an den Token-Dienst, der ihn mit dem Secret bei eBay eintauscht.
 */
class EbayAuth(
    private val config: EbayConfig,
    private val http: OkHttpClient = defaultClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun authorizeUrl(state: String): String = buildString {
        append(config.authHost).append("/oauth2/authorize")
        append("?client_id=").append(enc(config.clientId.trim()))
        append("&redirect_uri=").append(enc(config.ruName.trim()))
        append("&response_type=code")
        append("&scope=").append(enc(EbayConfig.SCOPES.joinToString(" ")))
        append("&state=").append(enc(state))
        append("&locale=de-DE")
        // Immer nach den Zugangsdaten fragen, damit man ein anderes eBay-Konto wählen kann.
        append("&prompt=login")
    }

    /**
     * Prüft, ob [url] die Weiterleitung nach der Anmeldung ist. Erkannt wird sie am eigenen `state`,
     * damit die App die Adresse des Token-Dienstes nicht kennen muss. Null: noch nicht fertig.
     */
    fun parseRedirect(url: String, expectedState: String): EbayRedirect? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val state = parsed.queryParameter("state")
        val code = parsed.queryParameter("code")
        val error = parsed.queryParameter("error")
        return when {
            state != expectedState -> null
            !code.isNullOrBlank() -> EbayRedirect.Code(code)
            !error.isNullOrBlank() -> EbayRedirect.Denied(parsed.queryParameter("error_description") ?: error)
            else -> null
        }
    }

    fun exchangeCode(code: String): EbayTokens {
        val json = callService("token", mapOf("code" to code))
        return parseTokens(json, previous = null)
    }

    fun refresh(tokens: EbayTokens): EbayTokens {
        if (!tokens.refreshValid(clock())) throw EbayException("Die eBay-Anmeldung ist abgelaufen. Bitte neu anmelden.")
        val json = callService("refresh", mapOf("refresh_token" to tokens.refreshToken, "scope" to EbayConfig.SCOPES.joinToString(" ")))
        return parseTokens(json, previous = tokens)
    }

    private fun callService(path: String, body: Map<String, String>): JsonNode {
        val url = config.tokenServiceUrl.trim().trimEnd('/') + "/" + path
        val request = try {
            Request.Builder().url(url).post(mapper.writeValueAsString(body + ("sandbox" to config.sandbox.toString())).toRequestBody(JSON)).build()
        } catch (e: IllegalArgumentException) {
            throw EbayException("Die Adresse des eBay-Token-Dienstes ist ungültig.", e)
        }
        return try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val node = runCatching { mapper.readTree(text) }.getOrNull()
                if (response.code !in 200..299 || node == null) {
                    val detail = node?.path("error_description")?.asText(null) ?: node?.path("error")?.asText(null)
                    throw EbayException(
                        "eBay-Anmeldung fehlgeschlagen (Fehler ${response.code})" + (detail?.let { ": $it" } ?: "."),
                    )
                }
                node
            }
        } catch (e: IOException) {
            throw EbayException("Der eBay-Token-Dienst ist nicht erreichbar. Bitte Internetverbindung prüfen.", e)
        }
    }

    private fun parseTokens(json: JsonNode, previous: EbayTokens?): EbayTokens {
        val now = clock()
        val access = json.path("access_token").asText("")
        if (access.isBlank()) throw EbayException("eBay hat keinen Zugang geliefert. Bitte neu anmelden.")
        // Beim Erneuern schickt eBay keinen neuen Refresh-Token; der alte bleibt gültig.
        val refresh = json.path("refresh_token").asText("").ifBlank { previous?.refreshToken.orEmpty() }
        val refreshExpires = json.path("refresh_token_expires_in").takeIf { it.isNumber }
            ?.let { now + it.asLong() * 1000 } ?: previous?.refreshExpiresAt ?: 0L
        return EbayTokens(
            accessToken = access,
            accessExpiresAt = now + json.path("expires_in").asLong(7200) * 1000,
            refreshToken = refresh,
            refreshExpiresAt = refreshExpires,
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    internal companion object {
        val JSON = "application/json".toMediaType()
        val mapper = ObjectMapper()
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
