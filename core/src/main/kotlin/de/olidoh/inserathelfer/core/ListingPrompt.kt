package de.olidoh.inserathelfer.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** Fehler mit einer Meldung, die man dem Nutzer direkt anzeigen kann. */
class ListingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Erzeugt aus Fotos und Hinweisen einen Anzeigenentwurf. Blockiert, also nicht auf dem Main-Thread aufrufen. */
interface ListingGenerator {
    /**
     * @param jpegImages bereits verkleinerte JPEG-Bilder (lange Kante etwa 1568 px)
     * @param sellerNotes freie Hinweise des Verkäufers, z. B. Mängel oder Lieferumfang
     */
    fun generate(jpegImages: List<ByteArray>, sellerNotes: String = ""): ListingDraft

    companion object {
        /** Mehr Fotos verbessern das Ergebnis kaum, kosten aber mehr. */
        const val MAX_IMAGES = 5

        fun create(config: AiConfig): ListingGenerator = when (config.provider.kind) {
            AiProvider.Kind.CLAUDE -> ClaudeListingGenerator(config.apiKey, config.model)
            AiProvider.Kind.OPENAI_COMPATIBLE -> OpenAiCompatibleListingGenerator(config)
        }
    }
}

/** Prompt, Antwortformat und Auswertung, gemeinsam für alle Anbieter. */
object ListingPrompt {

    val SYSTEM_PROMPT = """
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

    /** Zusatz für Anbieter ohne festes Antwortschema: beschreibt das JSON-Format im Text. */
    val JSON_INSTRUCTIONS = """
        Antworte ausschließlich mit einem JSON-Objekt ohne weiteren Text, mit genau diesen Feldern:
        {"title": string, "description": string, "priceEur": integer, "priceType": "FIXED" | "NEGOTIABLE" | "FREE",
         "category": string, "condition": "Neu" | "Sehr gut" | "Gut" | "In Ordnung" | "Defekt", "priceReasoning": string}
    """.trimIndent()

    val REQUIRED_FIELDS = listOf("title", "description", "priceEur", "priceType", "category", "condition", "priceReasoning")

    /** JSON-Schema der Antwort als einfache Map (für Anbieter mit festem Antwortschema). */
    val SCHEMA_PROPERTIES: Map<String, Any> = mapOf(
        "title" to mapOf("type" to "string", "description" to "Anzeigentitel, höchstens 65 Zeichen"),
        "description" to mapOf("type" to "string", "description" to "Anzeigentext"),
        "priceEur" to mapOf("type" to "integer", "description" to "Preisvorschlag in ganzen Euro"),
        "priceType" to mapOf("type" to "string", "enum" to PriceType.entries.map { it.name }),
        "category" to mapOf("type" to "string", "description" to "Kategoriepfad bei Kleinanzeigen"),
        "condition" to mapOf("type" to "string", "enum" to CONDITIONS),
        "priceReasoning" to mapOf("type" to "string", "description" to "Kurze Begründung des Preises"),
    )

    fun userPrompt(sellerNotes: String): String {
        val notes = sellerNotes.trim()
        val base = "Erstelle eine Anzeige für den Artikel auf den Fotos."
        return if (notes.isEmpty()) base else "$base\n\nHinweise des Verkäufers:\n$notes"
    }

    private val mapper = ObjectMapper()

    /** Liest die Antwort der KI. Verträgt auch Antworten in ```json-Blöcken. */
    fun parseDraftJson(raw: String): ListingDraft {
        val json = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
            .let { s ->
                val start = s.indexOf('{')
                val end = s.lastIndexOf('}')
                if (start >= 0 && end > start) s.substring(start, end + 1) else s
            }
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
}
