package de.olidoh.inserathelfer.core.ebay

import de.olidoh.inserathelfer.core.ListingDraft
import de.olidoh.inserathelfer.core.PriceType

/** Übersetzt den Anzeigenentwurf in die Felder, die eBay erwartet. */
object EbayMapping {

    /** eBay erlaubt höchstens 80 Zeichen im Titel. */
    const val MAX_TITLE_LENGTH = 80

    /** eBay-Zustandsnummern und ihre Namen in der Inventory API. */
    private val CONDITION_ENUMS = mapOf(
        "1000" to "NEW",
        "1500" to "NEW_OTHER",
        "1750" to "NEW_WITH_DEFECTS",
        "2000" to "CERTIFIED_REFURBISHED",
        "2010" to "EXCELLENT_REFURBISHED",
        "2020" to "VERY_GOOD_REFURBISHED",
        "2030" to "GOOD_REFURBISHED",
        "2500" to "SELLER_REFURBISHED",
        "2750" to "LIKE_NEW",
        "2990" to "PRE_OWNED_EXCELLENT",
        "3000" to "USED_EXCELLENT",
        "3010" to "PRE_OWNED_FAIR",
        "4000" to "USED_VERY_GOOD",
        "5000" to "USED_GOOD",
        "6000" to "USED_ACCEPTABLE",
        "7000" to "FOR_PARTS_OR_NOT_WORKING",
    )

    fun conditionEnum(id: String): String? = CONDITION_ENUMS[id]

    /** Welche eBay-Zustände (in dieser Reihenfolge) zu unseren Zuständen passen. */
    private fun preferredIds(condition: String): List<String> = when (condition.trim().lowercase()) {
        "neu" -> listOf("1000", "1500", "2750")
        "sehr gut" -> listOf("4000", "2750", "2990", "3000")
        "gut" -> listOf("5000", "3000", "2990")
        "in ordnung" -> listOf("6000", "3010", "3000")
        "defekt" -> listOf("7000")
        else -> listOf("3000")
    }

    /** Wählt aus den erlaubten Zuständen den passendsten; null, wenn die Kategorie keinen Zustand kennt. */
    fun pickCondition(condition: String, allowed: List<EbayCondition>): EbayCondition? {
        if (allowed.isEmpty()) return null
        val byId = allowed.associateBy { it.id }
        return preferredIds(condition).firstNotNullOfOrNull { byId[it] }
            ?: byId["3000"]
            ?: allowed.first()
    }

    /** Ersatz, falls eBay die erlaubten Zustände nicht liefern konnte. */
    fun fallbackConditionEnum(condition: String): String =
        CONDITION_ENUMS.getValue(preferredIds(condition).first())

    fun title(draft: ListingDraft): String = draft.title.trim().take(MAX_TITLE_LENGTH)

    /** Preis für eBay: „Zu verschenken“ gibt es dort nicht, mindestens 1 €. */
    fun price(draft: ListingDraft): Int = if (draft.priceType == PriceType.FREE) 1 else draft.priceEur.coerceAtLeast(1)

    fun bestOffer(draft: ListingDraft): Boolean = draft.priceType == PriceType.NEGOTIABLE

    /** eBay zeigt die Beschreibung als HTML an; Zeilenumbrüche bleiben so erhalten. */
    fun descriptionHtml(text: String): String =
        text.trim()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\r\n", "\n")
            .replace("\n", "<br>")

    internal fun inventoryItemJson(offer: EbayOffer): String {
        val item = buildMap {
            put("availability", mapOf("shipToLocationAvailability" to mapOf("quantity" to 1)))
            put("condition", offer.conditionEnum)
            // Neue Artikel dürfen bei eBay keine Zustandsbeschreibung haben.
            if (offer.conditionNote.isNotBlank() && !offer.conditionEnum.startsWith("NEW")) {
                put("conditionDescription", offer.conditionNote.take(1000))
            }
            put(
                "product",
                buildMap {
                    put("title", offer.title.take(MAX_TITLE_LENGTH))
                    put("description", offer.description)
                    put("imageUrls", offer.imageUrls)
                    val aspects = offer.aspects.filterValues { it.isNotBlank() }.mapValues { listOf(it.value.trim()) }
                    if (aspects.isNotEmpty()) put("aspects", aspects)
                },
            )
        }
        return EbayAuth.mapper.writeValueAsString(item)
    }

    internal fun offerJson(offer: EbayOffer, forUpdate: Boolean = false): String {
        val policies = buildMap {
            put("fulfillmentPolicyId", offer.fulfillmentPolicyId)
            put("paymentPolicyId", offer.paymentPolicyId)
            put("returnPolicyId", offer.returnPolicyId)
            if (offer.bestOffer) put("bestOfferTerms", mapOf("bestOfferEnabled" to true))
        }
        val body = buildMap {
            if (!forUpdate) {
                put("sku", offer.sku)
                put("marketplaceId", EbayClient.MARKETPLACE)
                put("format", "FIXED_PRICE")
            }
            put("availableQuantity", 1)
            put("categoryId", offer.categoryId)
            put("listingDescription", offer.description)
            put("listingDuration", "GTC")
            put("listingPolicies", policies)
            put("merchantLocationKey", offer.locationKey)
            put("pricingSummary", mapOf("price" to mapOf("value" to "${offer.priceEur}.00", "currency" to "EUR")))
        }
        return EbayAuth.mapper.writeValueAsString(body)
    }
}
