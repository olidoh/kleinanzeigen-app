package de.olidoh.inserathelfer.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Speichert die API-Schlüssel des Nutzers verschlüsselt, einen je KI-Anbieter.
 *
 * Der AES-Schlüssel liegt im Android Keystore und verlässt das Gerät nie; in den
 * SharedPreferences steht nur der verschlüsselte Text. Ohne diesen Keystore-Eintrag
 * (z. B. nach einer Wiederherstellung auf einem anderen Gerät) ist er wertlos.
 */
class ApiKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(provider: String): String? {
        val stored = prefs.getString(prefKey(provider), null) ?: return null
        return runCatching {
            val (ivPart, dataPart) = stored.split(":", limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(ivPart, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(dataPart, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse {
            // Nicht mehr entschlüsselbar: verwerfen, der Nutzer gibt den Schlüssel neu ein.
            clear(provider)
            null
        }
    }

    fun save(provider: String, apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(prefKey(provider), value).apply()
    }

    fun clear(provider: String) {
        prefs.edit().remove(prefKey(provider)).apply()
    }

    /** Ältere Versionen hatten nur einen Claude-Schlüssel unter "claude_api_key_encrypted". */
    private fun prefKey(provider: String) = "${provider.lowercase()}_api_key_encrypted"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "claude_api_key" // Alias bleibt, damit gespeicherte Schlüssel lesbar bleiben
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFS = "secure_settings"
    }
}
