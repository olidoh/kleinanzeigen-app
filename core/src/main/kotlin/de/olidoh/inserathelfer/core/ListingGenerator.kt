package de.olidoh.inserathelfer.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
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
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64

/** Fehler mit einer Meldung, die man dem Nutzer direkt anzeigen kann. */
class ListingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Erzeugt aus Fotos (JPEG) und optionalen Hinweisen des Verkäufers einen Anzeigenentwurf
 * über die Claude API. Jeder Nutzer verwendet seinen eigenen API-Schlüssel.
 */
class ListingGenerator(
    private val client: AnthropicClient,
    private val model: String = DEFAULT_MODEL,
) {
    constructor(apiKey: String, model: String = DEFAULT_MODEL) : this(
        AnthropicOkHttpClient.builder().apiKey(apiKey).build(),
        model,
    )

    /**
     * Blockiert bis zur Antwort, also nicht auf dem Main-Thread aufrufen.
     * @param jpegImages bereits verkleinerte JPEG-Bilder (lange Kante etwa 1568 px)
     * @param sellerNotes freie Hinweise des Verkäufers, z. B. Mängel oder Lieferumfang
     */
    fun generate(jpegImages: List<ByteArray>, sellerNotes: String = ""): ListingDraft {
        require(jpegImages.isNotEmpty()) { "Mindestens ein Foto wird benötigt." }
        val message = try {
            client.messages().create(buildParams(jpegImages, sellerNotes))
        } catch (e: UnauthorizedException) {
            throw ListingException("Der Claude-API-Schlüssel ist ungültig. Bitte in den Einstellungen prüfen.", e)
        } catch (e: PermissionDeniedException) {
            throw ListingException("Der API-Schlüssel hat keinen Zugriff auf das Modell $model.", e)
        } catch (e: RateLimitException) {
            throw ListingException("Zu viele Anfragen oder Guthaben aufgebraucht. Bitte später erneut versuchen.", e)
        } catch (e: AnthropicServiceException) {
            throw ListingException("Die KI ist gerade nicht erreichbar (Fehler ${e.statusCode()}).", e)
        } catch (e: AnthropicIoException) {
            throw ListingException("Keine Verbindung zur KI. Bitte Internetverbindung prüfen.", e)
        }
        return parseMessage(message)
    }

    internal fun buildParams(jpegImages: List<ByteArray>, sellerNotes: String): MessageCreateParams {
        val content = buildList {
            jpegImages.take(MAX_IMAGES).forEach { bytes ->
                add(
                    ContentBlockParam.ofImage(
                        Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                            .data(Base64.getEncoder().encodeToString(bytes))
                            .build(),
                    ),
                )
            }
            add(ContentBlockParam.ofText(userPrompt(sellerNotes)))
        }
        return MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(SYSTEM_PROMPT)
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
        return parseDraftJson(json)
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"

        /** Mehr Fotos verbessern das Ergebnis kaum, kosten aber mehr. */
        const val MAX_IMAGES = 5

        private val mapper = ObjectMapper()

        internal fun parseDraftJson(json: String): ListingDraft {
            val node: JsonNode = try {
                mapper.readTree(json)
            } catch (e: Exception) {
                throw ListingException("Die Antwort der KI konnte nicht gelesen werden.", e)
            }
            fun text(field: String): String = node.path(field).asText("").trim()

            val title = text("title")
            if (title.isEmpty()) throw ListingException("Die KI hat keinen Titel geliefert.")
            val priceType = runCatching { PriceType.valueOf(text("priceType")) }.getOrDefault(PriceType.NEGOTIABLE)
            val condition = text("condition").takeIf { it in CONDITIONS } ?: "Gut"
            return ListingDraft(
                title = title.clampAtWord(ListingDraft.MAX_TITLE_LENGTH),
                description = text("description").take(ListingDraft.MAX_DESCRIPTION_LENGTH),
                priceEur = if (priceType == PriceType.FREE) 0 else node.path("priceEur").asInt(0).coerceAtLeast(0),
                priceType = priceType,
                category = text("category"),
                condition = condition,
                priceReasoning = text("priceReasoning"),
            )
        }

        private fun userPrompt(sellerNotes: String): String {
            val notes = sellerNotes.trim()
            return if (notes.isEmpty()) {
                "Erstelle eine Anzeige für den Artikel auf den Fotos."
            } else {
                "Erstelle eine Anzeige für den Artikel auf den Fotos.\n\nHinweise des Verkäufers:\n$notes"
            }
        }

        private val SYSTEM_PROMPT = """
            Du hilfst Privatpersonen, Verkaufsanzeigen für Kleinanzeigen (kleinanzeigen.de) zu schreiben.
            Du bekommst Fotos eines Artikels und manchmal Hinweise des Verkäufers. Die Hinweise des
            Verkäufers haben Vorrang vor deiner Einschätzung der Fotos.

            Titel: höchstens 65 Zeichen. Nenne Marke, Modell und das wichtigste Merkmal, damit Käufer
            die Anzeige über die Suche finden. Keine Großbuchstaben-Schreierei, keine Emojis.

            Beschreibung: sachlich und freundlich, auf Deutsch, in kurzen Absätzen. Beschreibe den
            Zustand ehrlich, auch sichtbare Gebrauchsspuren oder Mängel, sowie Lieferumfang und Maße,
            wenn sie erkennbar oder genannt sind. Behaupte nichts, was weder auf den Fotos zu sehen ist
            noch vom Verkäufer genannt wurde, zum Beispiel Funktionsfähigkeit, Alter oder Kaufbeleg.
            Schließe mit dem Satz: "Privatverkauf, keine Garantie oder Rücknahme."

            Preis: ganze Euro, realistisch für den deutschen Gebrauchtmarkt, so angesetzt, dass sich
            der Artikel voraussichtlich innerhalb von ein bis zwei Wochen verkauft. Preisart FIXED für
            Festpreis, NEGOTIABLE für Verhandlungsbasis (der übliche Fall), FREE für Verschenken.
            Begründe den Preis in priceReasoning in einem kurzen Satz.

            Kategorie: der passende Kategoriepfad von Kleinanzeigen, z. B. "Elektronik > Handy & Telefon"
            oder "Haus & Garten > Wohnzimmer".

            Zustand: genau einer von "Neu", "Sehr gut", "Gut", "In Ordnung", "Defekt".
        """.trimIndent()

        private val OUTPUT_SCHEMA: JsonOutputFormat.Schema = JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty(
                "properties",
                JsonValue.from(
                    mapOf(
                        "title" to mapOf("type" to "string", "description" to "Anzeigentitel, höchstens 65 Zeichen"),
                        "description" to mapOf("type" to "string", "description" to "Anzeigentext"),
                        "priceEur" to mapOf("type" to "integer", "description" to "Preisvorschlag in ganzen Euro"),
                        "priceType" to mapOf("type" to "string", "enum" to PriceType.entries.map { it.name }),
                        "category" to mapOf("type" to "string", "description" to "Kategoriepfad bei Kleinanzeigen"),
                        "condition" to mapOf("type" to "string", "enum" to CONDITIONS),
                        "priceReasoning" to mapOf("type" to "string", "description" to "Kurze Begründung des Preises"),
                    ),
                ),
            )
            .putAdditionalProperty(
                "required",
                JsonValue.from(
                    listOf("title", "description", "priceEur", "priceType", "category", "condition", "priceReasoning"),
                ),
            )
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()
    }
}
