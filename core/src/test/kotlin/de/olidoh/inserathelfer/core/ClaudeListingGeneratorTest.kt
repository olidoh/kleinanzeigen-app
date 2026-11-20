package de.olidoh.inserathelfer.core

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.jsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClaudeListingGeneratorTest {

    @Test
    fun `parses a complete answer`() {
        val draft = ListingPrompt.parseDraftJson(
            """
            {"title":"IKEA Poäng Sessel Birke, Bezug beige","description":"Gut erhaltener Sessel.",
             "priceEur":35,"priceType":"NEGOTIABLE","category":"Haus & Garten > Wohnzimmer",
             "condition":"Gut","priceReasoning":"Neupreis 99 €, leichte Gebrauchsspuren."}
            """.trimIndent(),
        )
        assertEquals("IKEA Poäng Sessel Birke, Bezug beige", draft.title)
        assertEquals(35, draft.priceEur)
        assertEquals(PriceType.NEGOTIABLE, draft.priceType)
        assertEquals("35 € VB", draft.priceLabel)
        assertTrue(draft.asShareText().contains("Preis: 35 € VB"))
    }

    @Test
    fun `clamps long titles at a word boundary`() {
        val longTitle = "Samsung Galaxy S23 Ultra 256 GB Phantom Black mit Originalverpackung und Ladekabel"
        val draft = ListingPrompt.parseDraftJson(
            """{"title":"$longTitle","description":"x","priceEur":500,"priceType":"FIXED",
               "category":"Elektronik > Handy & Telefon","condition":"Sehr gut","priceReasoning":"x"}""",
        )
        assertTrue(draft.title.length <= ListingDraft.MAX_TITLE_LENGTH)
        assertTrue(longTitle.startsWith(draft.title))
        assertTrue(!draft.title.endsWith(" "))
    }

    @Test
    fun `free items have price zero and unknown values fall back`() {
        val draft = ListingPrompt.parseDraftJson(
            """{"title":"Umzugskartons","description":"x","priceEur":12,"priceType":"FREE",
               "category":"Haus & Garten","condition":"Wie neu","priceReasoning":"x"}""",
        )
        assertEquals(0, draft.priceEur)
        assertEquals("Zu verschenken", draft.priceLabel)
        assertEquals("Gut", draft.condition)
    }

    @Test
    fun `invalid json gives a readable error`() {
        val e = assertFailsWith<ListingException> { ListingPrompt.parseDraftJson("kein json") }
        assertTrue(e.message!!.contains("nicht gelesen"))
    }

    @Test
    fun `request contains images, schema and fallback`() {
        val generator = ClaudeListingGenerator(AnthropicOkHttpClient.builder().apiKey("test").build())
        val params = generator.buildParams(List(7) { byteArrayOf(1, 2, 3) }, "Kratzer an der Seite")
        val body = jsonMapper().writeValueAsString(params._body())
        assertEquals(ListingGenerator.MAX_IMAGES, Regex("\"type\":\"image\"").findAll(body).count())
        assertTrue(body.contains("\"model\":\"claude-opus-5-5\""))
        assertTrue(body.contains("\"fallbacks\":\"default\""))
        assertTrue(body.contains("\"additionalProperties\":false"))
        assertTrue(body.contains("Kratzer an der Seite"))
        assertEquals(listOf("server-side-fallback-2026-07-01"), params._headers().values("anthropic-beta"))
    }
}
