package de.olidoh.inserathelfer.core

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Für Anbieter mit OpenAI-kompatiblem `/chat/completions`-Endpunkt, z. B. Mistral, Langdock oder OpenAI.
 * Bilder gehen als Base64-Data-URL mit, die Antwort wird als JSON-Objekt angefordert.
 */
class OpenAiCompatibleListingGenerator(
    private val config: AiConfig,
    private val http: OkHttpClient = defaultClient,
) : ListingGenerator {

    override fun generate(jpegImages: List<ByteArray>, sellerNotes: String, styleGuide: String): ListingDraft {
        require(jpegImages.isNotEmpty()) { "Mindestens ein Foto wird benötigt." }
        if (config.baseUrl.isBlank()) throw ListingException("Bitte in den Einstellungen eine Basis-URL angeben.")
        if (config.model.isBlank()) throw ListingException("Bitte in den Einstellungen ein Modell angeben.")

        val request = Request.Builder()
            .url(endpoint())
            .header("Authorization", "Bearer ${config.apiKey.trim()}")
            .post(mapper.writeValueAsString(buildBody(jpegImages, sellerNotes, styleGuide)).toRequestBody(JSON))
            .build()

        val body = try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when (response.code) {
                    in 200..299 -> text
                    401 -> throw ListingException("Der API-Schlüssel für ${config.provider.label} ist ungültig.")
                    403 -> throw ListingException("Der API-Schlüssel hat keinen Zugriff auf das Modell ${config.model}.")
                    404 -> throw ListingException("Modell oder Adresse nicht gefunden. Bitte Modell und Basis-URL prüfen.")
                    429 -> throw ListingException("Zu viele Anfragen oder Guthaben aufgebraucht. Bitte später erneut versuchen.")
                    400, 422 -> throw ListingException(
                        "Der Anbieter hat die Anfrage abgelehnt. Versteht das Modell ${config.model} Bilder?" +
                            errorDetail(text),
                    )
                    else -> throw ListingException("Die KI ist gerade nicht erreichbar (Fehler ${response.code}).")
                }
            }
        } catch (e: IOException) {
            throw ListingException("Keine Verbindung zur KI. Bitte Internetverbindung prüfen.", e)
        } catch (e: IllegalArgumentException) {
            throw ListingException("Die Basis-URL ist ungültig.", e)
        }

        val choice = mapper.readTree(body).path("choices").path(0)
        if (choice.path("finish_reason").asText() == "length") {
            throw ListingException("Die Antwort der KI war unvollständig. Bitte erneut versuchen.")
        }
        val content = choice.path("message").path("content").asText("")
        if (content.isBlank()) throw ListingException("Die KI hat keine Antwort geliefert.")
        return ListingPrompt.parseDraftJson(content)
    }

    internal fun endpoint(): String = config.baseUrl.trim().trimEnd('/') + "/chat/completions"

    internal fun buildBody(jpegImages: List<ByteArray>, sellerNotes: String, styleGuide: String = ""): Map<String, Any> {
        val userContent = buildList {
            jpegImages.take(ListingGenerator.MAX_IMAGES).forEach { bytes ->
                val dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
                add(mapOf("type" to "image_url", "image_url" to mapOf("url" to dataUrl)))
            }
            add(mapOf("type" to "text", "text" to ListingPrompt.userPrompt(sellerNotes, styleGuide)))
        }
        return mapOf(
            "model" to config.model.trim(),
            "messages" to listOf(
                mapOf(
                    "role" to "system",
                    "content" to ListingPrompt.SYSTEM_PROMPT + "\n\n" + ListingPrompt.JSON_INSTRUCTIONS,
                ),
                mapOf("role" to "user", "content" to userContent),
            ),
            "response_format" to mapOf("type" to "json_object"),
        )
    }

    private fun errorDetail(body: String): String {
        val message = runCatching {
            val node = mapper.readTree(body)
            node.path("error").path("message").asText(null) ?: node.path("message").asText(null)
        }.getOrNull()
        return if (message.isNullOrBlank()) "" else " ($message)"
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val mapper = ObjectMapper()
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .callTimeout(4, TimeUnit.MINUTES)
            .build()
    }
}
