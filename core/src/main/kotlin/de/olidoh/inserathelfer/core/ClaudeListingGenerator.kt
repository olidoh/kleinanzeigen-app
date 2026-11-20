package de.olidoh.inserathelfer.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import java.util.Base64

/**
 * Anzeigenentwurf über die Claude API (offizielles Anthropic-SDK).
 *
 * Mit [baseUrl] lässt sich ein Anthropic-kompatibler Zugang wie Langdock nutzen. Dort stehen nicht
 * alle Funktionen der Anthropic-API bereit, daher werden dann festes Antwortschema, Effort und
 * Ausweichmodell weggelassen und das JSON-Format im Prompt beschrieben.
 */
class ClaudeListingGenerator(
    private val client: AnthropicClient,
    private val model: String = DEFAULT_MODEL,
    private val viaAnthropic: Boolean = true,
) : ListingGenerator {
    constructor(apiKey: String, model: String = DEFAULT_MODEL, baseUrl: String = ANTHROPIC_BASE_URL) : this(
        AnthropicOkHttpClient.builder()
            .apiKey(apiKey.trim())
            .baseUrl(baseUrl.trim().trimEnd('/').ifEmpty { ANTHROPIC_BASE_URL })
            .build(),
        model.trim(),
        viaAnthropic = baseUrl.trim().trimEnd('/').ifEmpty { ANTHROPIC_BASE_URL } == ANTHROPIC_BASE_URL,
    )

    override fun generate(jpegImages: List<ByteArray>, sellerNotes: String): ListingDraft {
        require(jpegImages.isNotEmpty()) { "Mindestens ein Foto wird benötigt." }
        val message = try {
            client.messages().create(buildParams(jpegImages, sellerNotes))
        } catch (e: UnauthorizedException) {
            throw ListingException("Der API-Schlüssel ist ungültig. Bitte in den Einstellungen prüfen.", e)
        } catch (e: PermissionDeniedException) {
            throw ListingException("Der API-Schlüssel hat keinen Zugriff auf das Modell $model.", e)
        } catch (e: RateLimitException) {
            throw ListingException("Zu viele Anfragen oder Guthaben aufgebraucht. Bitte später erneut versuchen.", e)
        } catch (e: NotFoundException) {
            throw ListingException("Modell $model nicht gefunden. Bitte den Modellnamen in den Einstellungen prüfen.", e)
        } catch (e: BadRequestException) {
            throw ListingException("Der Anbieter hat die Anfrage abgelehnt (${e.message}).", e)
        } catch (e: AnthropicServiceException) {
            throw ListingException("Die KI ist gerade nicht erreichbar (Fehler ${e.statusCode()}).", e)
        } catch (e: AnthropicIoException) {
            throw ListingException("Keine Verbindung zur KI. Bitte Internetverbindung prüfen.", e)
        }
        return parseMessage(message)
    }

    internal fun buildParams(jpegImages: List<ByteArray>, sellerNotes: String): MessageCreateParams {
        val content = buildList {
            jpegImages.take(ListingGenerator.MAX_IMAGES).forEach { bytes ->
                add(
                    ContentBlockParam.ofImage(
                        Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                            .data(Base64.getEncoder().encodeToString(bytes))
                            .build(),
                    ),
                )
            }
            add(ContentBlockParam.ofText(ListingPrompt.userPrompt(sellerNotes)))
        }
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
        if (viaAnthropic) {
            builder
                .system(ListingPrompt.SYSTEM_PROMPT)
                .outputConfig(
                    OutputConfig.builder()
                        .effort(OutputConfig.Effort.MEDIUM)
                        .format(JsonOutputFormat.builder().schema(OUTPUT_SCHEMA).build())
                        .build(),
                )
                // Falls ein Sicherheitsfilter die Anfrage ablehnt, darf die API automatisch
                // auf ein passendes anderes Modell ausweichen.
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        } else {
            builder.system(ListingPrompt.SYSTEM_PROMPT + "\n\n" + ListingPrompt.JSON_INSTRUCTIONS)
        }
        return builder
            .addUserMessageOfContentBlockParams(content)
            .build()
    }

    private fun parseMessage(message: Message): ListingDraft {
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL ->
                throw ListingException("Die KI hat die Anfrage abgelehnt. Bitte andere Fotos verwenden.")
            StopReason.MAX_TOKENS ->
                throw ListingException("Die Antwort der KI war unvollständig. Bitte erneut versuchen.")
            else -> Unit
        }
        val json = message.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .joinToString("")
        return ListingPrompt.parseDraftJson(json)
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val ANTHROPIC_BASE_URL = "https://api.anthropic.com"

        private val OUTPUT_SCHEMA: JsonOutputFormat.Schema = JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("properties", JsonValue.from(ListingPrompt.SCHEMA_PROPERTIES))
            .putAdditionalProperty("required", JsonValue.from(ListingPrompt.REQUIRED_FIELDS))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()
    }
}
