package de.olidoh.inserathelfer.data

import android.content.Context
import de.olidoh.inserathelfer.core.AiConfig
import de.olidoh.inserathelfer.core.AiProvider

/**
 * Gewählter KI-Anbieter sowie Modell und Basis-URL je Anbieter (nicht geheim, daher unverschlüsselt).
 * Die API-Schlüssel selbst liegen verschlüsselt im [ApiKeyStore].
 */
class AiSettings(context: Context) {

    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
    private val keys = ApiKeyStore(context)

    var selected: AiProvider
        get() = prefs.getString("provider", null)
            ?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() }
            ?: AiProvider.CLAUDE
        set(value) = prefs.edit().putString("provider", value.name).apply()

    fun model(provider: AiProvider): String =
        prefs.getString("${provider.name}_model", null) ?: provider.defaultModel

    fun baseUrl(provider: AiProvider): String =
        prefs.getString("${provider.name}_base_url", null) ?: provider.defaultBaseUrl

    fun apiKey(provider: AiProvider): String? = keys.load(provider.name)

    fun save(provider: AiProvider, apiKey: String?, model: String, baseUrl: String) {
        if (!apiKey.isNullOrBlank()) keys.save(provider.name, apiKey)
        prefs.edit()
            .putString("${provider.name}_model", model.trim())
            .putString("${provider.name}_base_url", baseUrl.trim())
            .putString("provider", provider.name)
            .apply()
    }

    fun removeKey(provider: AiProvider) = keys.clear(provider.name)

    /** Konfiguration des gewählten Anbieters, oder null, wenn dafür noch kein Schlüssel hinterlegt ist. */
    fun currentConfig(): AiConfig? {
        val provider = selected
        val key = apiKey(provider) ?: return null
        return AiConfig(provider, key, model(provider), baseUrl(provider))
    }
}
