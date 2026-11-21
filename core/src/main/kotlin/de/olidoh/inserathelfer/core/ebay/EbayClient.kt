package de.olidoh.inserathelfer.core.ebay

import com.fasterxml.jackson.databind.JsonNode
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class EbayCategory(val id: String, val name: String, val path: String)

/** Artikelmerkmal einer Kategorie, z. B. „Marke“. Pflichtmerkmale müssen vor dem Einstellen gefüllt sein. */
data class EbayAspect(
    val name: String,
    val required: Boolean,
    val values: List<String>,
    /** false: nur Werte aus [values] sind erlaubt. */
    val freeText: Boolean,
)

/** Ein Artikelzustand, wie eBay ihn für die Kategorie erlaubt; [label] ist eBays deutscher Name. */
data class EbayCondition(val id: String, val enumName: String, val label: String)

data class EbayPolicy(val id: String, val name: String)

data class EbayPolicies(
    val fulfillment: List<EbayPolicy>,
    val payment: List<EbayPolicy>,
    val returns: List<EbayPolicy>,
) {
    val isComplete get() = fulfillment.isNotEmpty() && payment.isNotEmpty() && returns.isNotEmpty()
}

/** Alles, was für ein Angebot zum Festpreis auf ebay.de nötig ist. */
data class EbayOffer(
    val sku: String,
    val title: String,
    val description: String,
    val priceEur: Int,
    val bestOffer: Boolean,
    val categoryId: String,
    val conditionEnum: String,
    val conditionNote: String,
    val aspects: Map<String, String>,
    val imageUrls: List<String>,
    val fulfillmentPolicyId: String,
    val paymentPolicyId: String,
    val returnPolicyId: String,
    val locationKey: String,
)

data class EbayPublishResult(val listingId: String, val url: String, val warnings: List<String>)

/**
 * Stellt Anzeigen über die offiziellen Verkäufer-Schnittstellen von eBay ein (Marktplatz ebay.de):
 * Fotos über die Media API, Kategorie und Merkmale über die Taxonomy API, Zustände über die
 * Metadata API, Versand/Zahlung/Rücknahme über die Account API und das Angebot über die Inventory API.
 *
 * [accessToken] liefert einen gültigen Nutzer-Zugang (bei Bedarf erneuert).
 */
class EbayClient(
    private val config: EbayConfig,
    private val accessToken: () -> String,
    private val http: OkHttpClient = defaultClient,
    private val apiHost: String = config.apiHost,
    private val mediaHost: String = config.mediaHost,
    private val identityHost: String = config.identityHost,
) {

    /** eBay-Benutzername des angemeldeten Kontos. */
    fun userName(): String =
        get("$identityHost/commerce/identity/v1/user/").path("username").asText("")


    private var categoryTreeId: String? = null

    fun suggestCategories(query: String): List<EbayCategory> {
        val tree = categoryTree()
        val url = "$apiHost/commerce/taxonomy/v1/category_tree/$tree/get_category_suggestions".toHttpUrl()
            .newBuilder().addQueryParameter("q", query.take(350)).build().toString()
        return get(url).path("categorySuggestions").map { s ->
            val c = s.path("category")
            val ancestors = s.path("categoryTreeNodeAncestors").map { it.path("categoryName").asText() }.reversed()
            EbayCategory(
                id = c.path("categoryId").asText(),
                name = c.path("categoryName").asText(),
                path = (ancestors + c.path("categoryName").asText()).joinToString(" > "),
            )
        }.filter { it.id.isNotBlank() }
    }

    fun aspects(categoryId: String): List<EbayAspect> {
        val url = "$apiHost/commerce/taxonomy/v1/category_tree/${categoryTree()}/get_item_aspects_for_category".toHttpUrl()
            .newBuilder().addQueryParameter("category_id", categoryId).build().toString()
        return get(url).path("aspects").map { a ->
            val c = a.path("aspectConstraint")
            EbayAspect(
                name = a.path("localizedAspectName").asText(),
                required = c.path("aspectRequired").asBoolean(false),
                values = a.path("aspectValues").map { it.path("localizedValue").asText() }.filter(String::isNotBlank),
                freeText = c.path("aspectMode").asText("FREE_TEXT") != "SELECTION_ONLY",
            )
        }.filter { it.name.isNotBlank() }
    }

    /** Erlaubte Zustände der Kategorie; leer, wenn eBay die Kategorie ohne Zustand führt. */
    fun conditions(categoryId: String): List<EbayCondition> {
        val url = "$apiHost/sell/metadata/v1/marketplace/$MARKETPLACE/get_item_condition_policies".toHttpUrl()
            .newBuilder().addQueryParameter("filter", "categoryIds:{$categoryId}").build().toString()
        return get(url).path("itemConditionPolicies").path(0).path("itemConditions").mapNotNull { c ->
            val id = c.path("conditionId").asText()
            val enumName = EbayMapping.conditionEnum(id) ?: return@mapNotNull null
            EbayCondition(id, enumName, c.path("conditionDescription").asText(enumName))
        }
    }

    /** Versand-, Zahlungs- und Rücknahmegrundsätze des Verkäufers bei ebay.de. */
    fun policies(): EbayPolicies {
        fun load(type: String, listField: String, idField: String) =
            get("$apiHost/sell/account/v1/$type?marketplace_id=$MARKETPLACE").path(listField).map {
                EbayPolicy(it.path(idField).asText(), it.path("name").asText())
            }
        return try {
            EbayPolicies(
                fulfillment = load("fulfillment_policy", "fulfillmentPolicies", "fulfillmentPolicyId"),
                payment = load("payment_policy", "paymentPolicies", "paymentPolicyId"),
                returns = load("return_policy", "returnPolicies", "returnPolicyId"),
            )
        } catch (e: EbayApiException) {
            // 20403: Konto nutzt noch keine Grundsätze. Einmal anmelden, dann legt der Nutzer sie bei eBay an.
            if (e.errorIds.contains(20403)) {
                runCatching {
                    send("POST", "$apiHost/sell/account/v1/program/opt_in", json("""{"programType":"SELLING_POLICY_MANAGEMENT"}"""))
                }
                EbayPolicies(emptyList(), emptyList(), emptyList())
            } else {
                throw e
            }
        }
    }

    /** Legt den Artikelstandort an (falls noch nicht vorhanden) und gibt seinen Schlüssel zurück. */
    fun ensureLocation(postalCode: String, city: String): String {
        val plz = postalCode.filter(Char::isDigit)
        if (plz.length != 5) throw EbayException("Bitte eine gültige deutsche Postleitzahl angeben.")
        val key = "verkora-$plz"
        val url = "$apiHost/sell/inventory/v1/location/$key"
        val exists = try {
            get(url)
            true
        } catch (e: EbayApiException) {
            if (e.status == 404) false else throw e
        }
        if (!exists) {
            val body = EbayAuth.mapper.writeValueAsString(
                mapOf(
                    "name" to "Verkora $plz",
                    "locationTypes" to listOf("WAREHOUSE"),
                    "merchantLocationStatus" to "ENABLED",
                    "location" to mapOf(
                        "address" to buildMap {
                            put("postalCode", plz)
                            put("country", "DE")
                            if (city.isNotBlank()) put("city", city.trim())
                        },
                    ),
                ),
            )
            send("POST", url, json(body))
        }
        return key
    }

    /** Lädt ein Foto zu eBay hoch und gibt die Bildadresse für das Angebot zurück. */
    fun uploadImage(jpeg: ByteArray, fileName: String): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", fileName, jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val node = send("POST", "$mediaHost/commerce/media/v1_beta/image/create_image_from_file", body)
        return node.path("imageUrl").asText("").ifBlank {
            throw EbayException("eBay hat für ein Foto keine Bildadresse geliefert.")
        }
    }

    /** Legt Artikel und Angebot an (oder aktualisiert sie) und veröffentlicht das Angebot. */
    fun publish(offer: EbayOffer): EbayPublishResult {
        val skuPath = java.net.URLEncoder.encode(offer.sku, "UTF-8")
        send("PUT", "$apiHost/sell/inventory/v1/inventory_item/$skuPath", json(EbayMapping.inventoryItemJson(offer)))

        val offerBody = EbayMapping.offerJson(offer)
        val offerId = existingOfferId(offer.sku)?.also { id ->
            send("PUT", "$apiHost/sell/inventory/v1/offer/$id", json(EbayMapping.offerJson(offer, forUpdate = true)))
        } ?: send("POST", "$apiHost/sell/inventory/v1/offer", json(offerBody)).path("offerId").asText("")
        if (offerId.isBlank()) throw EbayException("eBay hat das Angebot nicht angelegt.")

        val result = send("POST", "$apiHost/sell/inventory/v1/offer/$offerId/publish", json("{}"))
        val listingId = result.path("listingId").asText("")
        if (listingId.isBlank()) throw EbayException("eBay hat das Angebot nicht veröffentlicht.")
        val site = if (config.sandbox) "https://sandbox.ebay.de" else "https://www.ebay.de"
        return EbayPublishResult(
            listingId = listingId,
            url = "$site/itm/$listingId",
            warnings = result.path("warnings").map { it.path("message").asText() }.filter(String::isNotBlank),
        )
    }

    private fun existingOfferId(sku: String): String? {
        val url = "$apiHost/sell/inventory/v1/offer".toHttpUrl().newBuilder()
            .addQueryParameter("sku", sku).addQueryParameter("marketplace_id", MARKETPLACE).build().toString()
        return try {
            get(url).path("offers").firstOrNull { it.path("marketplaceId").asText() == MARKETPLACE }
                ?.path("offerId")?.asText()?.takeIf(String::isNotBlank)
        } catch (e: EbayApiException) {
            // 404 bzw. 25713: Zu dieser SKU gibt es noch kein Angebot.
            if (e.status == 404 || e.errorIds.contains(25713)) null else throw e
        }
    }

    private fun categoryTree(): String = categoryTreeId ?: get(
        "$apiHost/commerce/taxonomy/v1/get_default_category_tree_id?marketplace_id=$MARKETPLACE",
    ).path("categoryTreeId").asText(DEFAULT_TREE_DE).also { categoryTreeId = it }

    private fun get(url: String): JsonNode = send("GET", url, null)

    private fun json(text: String): RequestBody = text.toRequestBody(JSON)

    private fun send(method: String, url: String, body: RequestBody?): JsonNode {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${accessToken()}")
            .header("Accept-Language", "de-DE")
            .header("Content-Language", "de-DE")
            .header("X-EBAY-C-MARKETPLACE-ID", MARKETPLACE)
            .method(method, body)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val node = runCatching { EbayAuth.mapper.readTree(text.ifBlank { "{}" }) }.getOrNull()
                    ?: EbayAuth.mapper.createObjectNode()
                if (response.code !in 200..299) throw EbayApiException.from(response.code, node)
                node
            }
        } catch (e: IOException) {
            throw EbayException("eBay ist nicht erreichbar. Bitte Internetverbindung prüfen.", e)
        }
    }

    companion object {
        const val MARKETPLACE = "EBAY_DE"
        private const val DEFAULT_TREE_DE = "77"
        private val JSON = "application/json".toMediaType()
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .build()
    }
}

/** Fehlerantwort von eBay mit den Fehlernummern, damit die App gezielt reagieren kann. */
class EbayApiException(val status: Int, val errorIds: List<Int>, message: String) : EbayException(message) {
    companion object {
        fun from(status: Int, node: JsonNode): EbayApiException {
            val errors = node.path("errors")
            val ids = errors.map { it.path("errorId").asInt() }
            val text = errors.joinToString(" ") { e ->
                e.path("longMessage").asText("").ifBlank { e.path("message").asText("") }
            }.trim()
            val message = when (status) {
                401 -> "Die eBay-Anmeldung ist nicht mehr gültig. Bitte neu anmelden."
                403 -> "eBay erlaubt diesen Zugriff nicht." + if (text.isNotEmpty()) " ($text)" else ""
                429 -> "Zu viele Anfragen an eBay. Bitte kurz warten und erneut versuchen."
                else -> "eBay meldet: " + text.ifEmpty { "Fehler $status" }
            }
            return EbayApiException(status, ids, message)
        }
    }
}
