package de.olidoh.inserathelfer.core

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAiCompatibleListingGeneratorTest {

    private lateinit var server: MockWebServer
    private val mapper = ObjectMapper()

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun generator(provider: AiProvider = AiProvider.MISTRAL, model: String = "mistral-medium-2508") =
        OpenAiCompatibleListingGenerator(
            AiConfig(provider, apiKey = " key-123 ", model = model, baseUrl = server.url("/v1/").toString()),
        )

    private fun completion(content: String, finishReason: String = "stop") = MockResponse().setBody(
        mapper.writeValueAsString(
            mapOf("choices" to listOf(mapOf("finish_reason" to finishReason, "message" to mapOf("content" to content)))),
        ),
    )

    @Test
    fun `sends images as data urls and parses the answer`() {
        server.enqueue(
            completion(
                "```json\n{\"title\":\"Fahrradhelm Uvex, Größe M\",\"description\":\"Kaum benutzt.\",\"priceEur\":20," +
                    "\"priceType\":\"FIXED\",\"category\":\"Sport & Camping > Fahrrad & Zubehör\"," +
                    "\"condition\":\"Sehr gut\",\"priceReasoning\":\"Neupreis 60 €.\"}\n```",
            ),
        )
        val draft = generator().generate(List(6) { byteArrayOf(1, 2, 3) }, "Größe M")

        assertEquals("Fahrradhelm Uvex, Größe M", draft.title)
        assertEquals(20, draft.priceEur)
        assertEquals(PriceType.FIXED, draft.priceType)

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer key-123", request.getHeader("Authorization"))
        val body = mapper.readTree(request.body.readUtf8())
        assertEquals("mistral-medium-2508", body.path("model").asText())
        assertEquals("json_object", body.path("response_format").path("type").asText())
        val userContent = body.path("messages").path(1).path("content")
        val images = userContent.filter { it.path("type").asText() == "image_url" }
        assertEquals(ListingGenerator.MAX_IMAGES, images.size)
        assertTrue(images.first().path("image_url").path("url").asText().startsWith("data:image/jpeg;base64,"))
        assertTrue(userContent.last().path("text").asText().contains("Größe M"))
    }

    @Test
    fun `unauthorized gives a readable message`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"message\":\"Unauthorized\"}"))
        val e = assertFailsWith<ListingException> { generator().generate(listOf(byteArrayOf(1))) }
        assertTrue(e.message!!.contains("ungültig"))
    }

    @Test
    fun `bad request shows the provider's error`() {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("{\"error\":{\"message\":\"Model does not support images\"}}"),
        )
        val e = assertFailsWith<ListingException> { generator().generate(listOf(byteArrayOf(1))) }
        assertTrue(e.message!!.contains("Model does not support images"))
    }

    @Test
    fun `truncated answer is reported`() {
        server.enqueue(completion("{\"title\":\"x", finishReason = "length"))
        val e = assertFailsWith<ListingException> { generator().generate(listOf(byteArrayOf(1))) }
        assertTrue(e.message!!.contains("unvollständig"))
    }

    @Test
    fun `missing model is reported before any request`() {
        val e = assertFailsWith<ListingException> { generator(AiProvider.CUSTOM, model = " ").generate(listOf(byteArrayOf(1))) }
        assertTrue(e.message!!.contains("Modell"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `factory picks the right implementation`() {
        assertTrue(ListingGenerator.create(AiConfig(AiProvider.CLAUDE, "k")) is ClaudeListingGenerator)
        assertTrue(ListingGenerator.create(AiConfig(AiProvider.LANGDOCK, "k")) is OpenAiCompatibleListingGenerator)
        assertEquals(
            "https://api.langdock.com/openai/eu/v1/chat/completions",
            OpenAiCompatibleListingGenerator(AiConfig(AiProvider.LANGDOCK, "k")).endpoint(),
        )
    }
}
