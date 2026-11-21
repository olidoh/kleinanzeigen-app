package de.olidoh.inserathelfer.data

import android.content.Context
import de.olidoh.inserathelfer.BuildConfig
import de.olidoh.inserathelfer.core.ebay.EbayConfig
import de.olidoh.inserathelfer.core.ebay.EbayTokens
import org.json.JSONObject

/** Plattform, auf der eine Anzeige eingestellt wird. */
enum class Platform(val label: String) {
    KLEINANZEIGEN("Kleinanzeigen"),
    EBAY("eBay"),
}

/**
 * eBay-Einstellungen: Daten der eBay-Anwendung (fest in der App, in den Einstellungen überschreibbar),
 * der Zugang des Nutzers (verschlüsselt) und zuletzt gewählte Angaben wie Postleitzahl und Grundsätze.
 */
class EbaySettings(context: Context) {

    private val prefs = context.getSharedPreferences("ebay_settings", Context.MODE_PRIVATE)
    private val secure = ApiKeyStore(context)

    var platform: Platform
        get() = prefs.getString("platform", null)?.let { runCatching { Platform.valueOf(it) }.getOrNull() } ?: Platform.KLEINANZEIGEN
        set(value) = prefs.edit().putString("platform", value.name).apply()

    /** Eigene Werte aus den Einstellungen; leer heißt: Werte aus dem App-Build verwenden. */
    var customClientId: String
        get() = prefs.getString("client_id", "").orEmpty()
        set(v) = prefs.edit().putString("client_id", v.trim()).apply()
    var customRuName: String
        get() = prefs.getString("runame", "").orEmpty()
        set(v) = prefs.edit().putString("runame", v.trim()).apply()
    var customServiceUrl: String
        get() = prefs.getString("service_url", "").orEmpty()
        set(v) = prefs.edit().putString("service_url", v.trim()).apply()
    var customSandbox: Boolean
        get() = prefs.getBoolean("sandbox", BuildConfig.EBAY_SANDBOX)
        set(v) = prefs.edit().putBoolean("sandbox", v).apply()

    fun config(): EbayConfig = EbayConfig(
        clientId = customClientId.ifBlank { BuildConfig.EBAY_CLIENT_ID },
        ruName = customRuName.ifBlank { BuildConfig.EBAY_RUNAME },
        tokenServiceUrl = customServiceUrl.ifBlank { BuildConfig.EBAY_TOKEN_SERVICE_URL },
        sandbox = customSandbox,
    )

    val hasBuiltInConfig: Boolean
        get() = BuildConfig.EBAY_CLIENT_ID.isNotBlank() && BuildConfig.EBAY_RUNAME.isNotBlank() &&
            BuildConfig.EBAY_TOKEN_SERVICE_URL.isNotBlank()

    var tokens: EbayTokens?
        get() = secure.load(TOKEN_KEY)?.let { raw ->
            runCatching {
                val n = JSONObject(raw)
                EbayTokens(
                    n.getString("access"), n.getLong("accessExpires"),
                    n.optString("refresh"), n.optLong("refreshExpires"),
                )
            }.getOrNull()
        }
        set(value) {
            if (value == null) {
                secure.clear(TOKEN_KEY)
            } else {
                val json = JSONObject()
                    .put("access", value.accessToken).put("accessExpires", value.accessExpiresAt)
                    .put("refresh", value.refreshToken).put("refreshExpires", value.refreshExpiresAt)
                secure.save(TOKEN_KEY, json.toString())
            }
        }

    /** eBay-Benutzername des angemeldeten Kontos (nur zur Anzeige). */
    var accountName: String
        get() = prefs.getString("account_name", "").orEmpty()
        set(v) = prefs.edit().putString("account_name", v.trim()).apply()

    var postalCode: String
        get() = prefs.getString("postal_code", "").orEmpty()
        set(v) = prefs.edit().putString("postal_code", v.trim()).apply()
    var city: String
        get() = prefs.getString("city", "").orEmpty()
        set(v) = prefs.edit().putString("city", v.trim()).apply()

    fun policyId(kind: String): String = prefs.getString("policy_$kind", "").orEmpty()
    fun savePolicyId(kind: String, id: String) = prefs.edit().putString("policy_$kind", id).apply()

    private companion object {
        const val TOKEN_KEY = "ebay_tokens"
    }
}
