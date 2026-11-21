package de.olidoh.inserathelfer.core.ebay

import com.fasterxml.jackson.databind.ObjectMapper
import de.olidoh.inserathelfer.core.ListingDraft
import de.olidoh.inserathelfer.core.PriceType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EbayTest {

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

    private fun config() = EbayConfig("client-1", "Oli-InseratHe-PRD-abc", server.url("/dienst/").toString())

    private fun client(): EbayClient {
        val base = server.url("/").toString().trimEnd('/')
        return EbayClient(config(), accessToken = { "tok" }, apiHost = base, mediaHost = base, identityHost = base)
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    @Test
    fun `authorize url carries runame, scopes and state`() {
        val url = EbayAuth(config()).authorizeUrl("st-1")
        assertTrue(url.startsWith("https://auth.ebay.com/oauth2/authorize?client_id=client-1"))
        assertTrue(url.contains("redirect_uri=Oli-InseratHe-PRD-abc"))
        assertTrue(url.contains("sell.inventory"))
        assertTrue(url.contains("%20"))
        assertTrue(url.contains("state=st-1"))
        assertTrue(url.contains("prompt=login"))
    }

    @Test
    fun `user name of the signed in account`() {
        server.enqueue(json("""{"userId":"u1","username":"oli_verkauft"}"""))
        assertEquals("oli_verkauft", client().userName())
        assertEquals("/commerce/identity/v1/user/", server.takeRequest().path)
    }

    @Test
    fun `redirect is recognised by its state only`() {
        val auth = EbayAuth(config())
        assertEquals(
            EbayRedirect.Code("v^1.1#abc"),
            auth.parseRedirect("https://dienst.example/eingeloggt?state=st-1&code=v%5E1.1%23abc&expires_in=299", "st-1"),
        )
        assertNull(auth.parseRedirect("https://dienst.example/eingeloggt?state=fremd&code=x", "st-1"))
        assertNull(auth.parseRedirect("https://signin.ebay.de/ws/eBayISAPI.dll", "st-1"))
        assertTrue(auth.parseRedirect("https://x.example/?state=st-1&error=access_denied", "st-1") is EbayRedirect.Denied)
    }

    @Test
    fun `code exchange goes to the token service and keeps refresh token on refresh`() {
        var now = 1_000_000L
        val auth = EbayAuth(config(), clock = { now })
        server.enqueue(json("""{"access_token":"a1","expires_in":7200,"refresh_token":"r1","refresh_token_expires_in":47304000}"""))
        val tokens = auth.exchangeCode("code-9")
        val request = server.takeRequest()
        assertEquals("/dienst/token", request.path)
        assertEquals("code-9", mapper.readTree(request.body.readUtf8()).path("code").asText())
        assertEquals("r1", tokens.refreshToken)
        assertEquals(now + 7_200_000, tokens.accessExpiresAt)

        now += 8_000_000
        assertTrue(!tokens.accessValid(now))
        server.enqueue(json("""{"access_token":"a2","expires_in":7200}"""))
        val refreshed = auth.refresh(tokens)
        val refreshBody = mapper.readTree(server.takeRequest().body.readUtf8())
        assertEquals("r1", refreshBody.path("refresh_token").asText())
        assertEquals("a2", refreshed.accessToken)
        assertEquals("r1", refreshed.refreshToken)
        assertEquals(tokens.refreshExpiresAt, refreshed.refreshExpiresAt)
    }

    @Test
    fun `token service error is shown`() {
        server.enqueue(json("""{"error":"invalid_grant","error_description":"code expired"}""", 400))
        val e = assertFailsWith<EbayException> { EbayAuth(config()).exchangeCode("x") }
        assertTrue(e.message!!.contains("code expired"))
    }

    @Test
    fun `category suggestions use the default tree of ebay de`() {
        server.enqueue(json("""{"categoryTreeId":"77"}"""))
        server.enqueue(
            json(
                """{"categorySuggestions":[{"category":{"categoryId":"9355","categoryName":"Handys & Smartphones"},
                "categoryTreeNodeAncestors":[{"categoryName":"Handys & Kommunikation","categoryTreeNodeLevel":1}]}]}""",
            ),
        )
        val result = client().suggestCategories("iPhone 12 64 GB")
        assertEquals("/commerce/taxonomy/v1/get_default_category_tree_id?marketplace_id=EBAY_DE", server.takeRequest().path)
        val second = server.takeRequest()
        assertTrue(second.path!!.startsWith("/commerce/taxonomy/v1/category_tree/77/get_category_suggestions?q=iPhone"))
        assertEquals("Bearer tok", second.getHeader("Authorization"))
        assertEquals(listOf(EbayCategory("9355", "Handys & Smartphones", "Handys & Kommunikation > Handys & Smartphones")), result)
    }

    @Test
    fun `condition picking prefers matching ebay condition`() {
        val allowed = listOf(
            EbayCondition("1000", "NEW", "Neu"),
            EbayCondition("3000", "USED_EXCELLENT", "Gebraucht"),
            EbayCondition("7000", "FOR_PARTS_OR_NOT_WORKING", "Als Ersatzteil / defekt"),
        )
        assertEquals("3000", EbayMapping.pickCondition("Gut", allowed)!!.id)
        assertEquals("1000", EbayMapping.pickCondition("Neu", allowed)!!.id)
        assertEquals("7000", EbayMapping.pickCondition("Defekt", allowed)!!.id)
        assertEquals("5000", EbayMapping.pickCondition("Gut", allowed + EbayCondition("5000", "USED_GOOD", "Gut"))!!.id)
        assertNull(EbayMapping.pickCondition("Gut", emptyList()))
    }

    @Test
    fun `price and description are adapted for ebay`() {
        val draft = ListingDraft("T".repeat(90), "a < b\nZeile 2", 0, PriceType.FREE, "", "Gut", "")
        assertEquals(1, EbayMapping.price(draft))
        assertEquals(80, EbayMapping.title(draft).length)
        assertEquals("a &lt; b<br>Zeile 2", EbayMapping.descriptionHtml(draft.description))
        assertTrue(EbayMapping.bestOffer(draft.copy(priceType = PriceType.NEGOTIABLE)))
    }

    private fun offer() = EbayOffer(
        sku = "abc-1", title = "Fahrrad", description = "Gut erhalten", priceEur = 120, bestOffer = true,
        categoryId = "177831", conditionEnum = "USED_GOOD", conditionNote = "Zustand: Gut",
        aspects = mapOf("Marke" to "Cube", "Farbe" to ""), imageUrls = listOf("https://i.ebayimg.com/1.jpg"),
        fulfillmentPolicyId = "f1", paymentPolicyId = "p1", returnPolicyId = "r1", locationKey = "inserathelfer-10115",
    )

    @Test
    fun `publish creates item, offer and publishes it`() {
        server.enqueue(json("", 204)) // inventory_item
        server.enqueue(json("""{"errors":[{"errorId":25713,"message":"not found"}]}""", 404)) // keine vorhandenen Angebote
        server.enqueue(json("""{"offerId":"o-7"}""", 201))
        server.enqueue(json("""{"listingId":"1234567890"}"""))

        val result = client().publish(offer())

        val item = server.takeRequest()
        assertEquals("PUT", item.method)
        assertEquals("/sell/inventory/v1/inventory_item/abc-1", item.path)
        assertEquals("de-DE", item.getHeader("Content-Language"))
        val itemJson = mapper.readTree(item.body.readUtf8())
        assertEquals("USED_GOOD", itemJson.path("condition").asText())
        assertEquals("Cube", itemJson.path("product").path("aspects").path("Marke").path(0).asText())
        assertTrue(itemJson.path("product").path("aspects").path("Farbe").isMissingNode)

        server.takeRequest() // Suche nach vorhandenem Angebot
        val create = mapper.readTree(server.takeRequest().body.readUtf8())
        assertEquals("EBAY_DE", create.path("marketplaceId").asText())
        assertEquals("120.00", create.path("pricingSummary").path("price").path("value").asText())
        assertTrue(create.path("listingPolicies").path("bestOfferTerms").path("bestOfferEnabled").asBoolean())

        assertEquals("/sell/inventory/v1/offer/o-7/publish", server.takeRequest().path)
        assertEquals("https://www.ebay.de/itm/1234567890", result.url)
    }

    @Test
    fun `publish updates an existing offer instead of creating a second one`() {
        server.enqueue(json("", 204))
        server.enqueue(json("""{"offers":[{"offerId":"o-old","marketplaceId":"EBAY_DE"}]}"""))
        server.enqueue(json("", 204))
        server.enqueue(json("""{"listingId":"42"}"""))

        client().publish(offer())

        server.takeRequest()
        server.takeRequest()
        val update = server.takeRequest()
        assertEquals("PUT", update.method)
        assertEquals("/sell/inventory/v1/offer/o-old", update.path)
        assertTrue(mapper.readTree(update.body.readUtf8()).path("sku").isMissingNode)
    }

    @Test
    fun `missing business policies trigger opt in`() {
        server.enqueue(json("""{"errors":[{"errorId":20403,"message":"User is not eligible for Business Policy."}]}""", 400))
        server.enqueue(json("{}"))
        val policies = client().policies()
        assertTrue(!policies.isComplete)
        server.takeRequest()
        assertEquals("/sell/account/v1/program/opt_in", server.takeRequest().path)
    }

    @Test
    fun `ebay error messages are passed through`() {
        server.enqueue(json("""{"errors":[{"errorId":25002,"message":"x","longMessage":"Marke fehlt."}]}""", 400))
        val e = assertFailsWith<EbayApiException> { client().aspects("1") }
        assertEquals(listOf(25002), e.errorIds)
        assertTrue(e.message!!.contains("Marke fehlt."))
    }
}
