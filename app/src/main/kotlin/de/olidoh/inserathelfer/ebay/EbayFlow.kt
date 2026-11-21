package de.olidoh.inserathelfer.ebay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.olidoh.inserathelfer.core.ListingDraft
import de.olidoh.inserathelfer.core.ebay.EbayAspect
import de.olidoh.inserathelfer.core.ebay.EbayAuth
import de.olidoh.inserathelfer.core.ebay.EbayCategory
import de.olidoh.inserathelfer.core.ebay.EbayClient
import de.olidoh.inserathelfer.core.ebay.EbayCondition
import de.olidoh.inserathelfer.core.ebay.EbayException
import de.olidoh.inserathelfer.core.ebay.EbayMapping
import de.olidoh.inserathelfer.core.ebay.EbayOffer
import de.olidoh.inserathelfer.core.ebay.EbayPolicies
import de.olidoh.inserathelfer.core.ebay.EbayPublishResult
import de.olidoh.inserathelfer.core.ebay.EbayRedirect
import de.olidoh.inserathelfer.data.EbaySettings
import de.olidoh.inserathelfer.image.ImagePreparer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Ablauf „Bei eBay einstellen“: Anmeldung, Vorschläge für Kategorie, Merkmale, Zustand und Grundsätze
 * laden, dann Fotos hochladen und das Angebot veröffentlichen. Hält den Zustand für die Bildschirme.
 */
class EbayFlow(val settings: EbaySettings, private val scope: CoroutineScope) {

    var loggedIn by mutableStateOf(settings.tokens?.refreshValid(System.currentTimeMillis()) == true)
        private set

    val isConfigured: Boolean get() = settings.config().isComplete

    // --- Anmeldung -------------------------------------------------------------------------------

    private var loginState = ""
    var loginBusy by mutableStateOf(false)
        private set
    var loginError by mutableStateOf<String?>(null)
        private set

    /** Neue Anmeldung beginnen; liefert die Adresse für den WebView. */
    fun startLogin(): String {
        loginState = UUID.randomUUID().toString()
        loginError = null
        return EbayAuth(settings.config()).authorizeUrl(loginState)
    }

    /**
     * Für jede Adresse, die der WebView öffnen will. True, wenn es die Rückleitung nach der Anmeldung
     * war; dann soll der WebView sie nicht mehr laden. [onDone] meldet Erfolg (true) oder Abbruch.
     */
    fun handleRedirect(url: String, onDone: (Boolean) -> Unit): Boolean {
        val auth = EbayAuth(settings.config())
        when (val result = auth.parseRedirect(url, loginState)) {
            null -> return false
            is EbayRedirect.Denied -> {
                loginError = "Anmeldung abgebrochen: ${result.reason}"
                onDone(false)
            }
            is EbayRedirect.Code -> {
                loginBusy = true
                scope.launch {
                    try {
                        val tokens = withContext(Dispatchers.IO) { auth.exchangeCode(result.code) }
                        settings.tokens = tokens
                        loggedIn = true
                        onDone(true)
                    } catch (e: EbayException) {
                        loginError = e.message
                        onDone(false)
                    } finally {
                        loginBusy = false
                    }
                }
            }
        }
        loginState = ""
        return true
    }

    fun logout() {
        settings.tokens = null
        loggedIn = false
    }

    /** Gültiger Zugang; erneuert ihn bei Bedarf. Nur außerhalb des Hauptthreads aufrufen. */
    @Synchronized
    private fun accessToken(): String {
        val now = System.currentTimeMillis()
        val tokens = settings.tokens ?: throw EbayException("Bitte zuerst bei eBay anmelden.")
        if (tokens.accessValid(now)) return tokens.accessToken
        return try {
            EbayAuth(settings.config()).refresh(tokens).also { settings.tokens = it }.accessToken
        } catch (e: EbayException) {
            if (!tokens.refreshValid(now)) {
                settings.tokens = null
                loggedIn = false
            }
            throw e
        }
    }

    private var client: EbayClient? = null
    private fun client(): EbayClient = client ?: EbayClient(settings.config(), ::accessToken).also { client = it }

    // --- Angaben zum Angebot ---------------------------------------------------------------------

    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<EbayPublishResult?>(null)
        private set

    var title by mutableStateOf("")
    var priceEur by mutableStateOf(0)
    var bestOffer by mutableStateOf(false)
    var categoryQuery by mutableStateOf("")
    var categories by mutableStateOf<List<EbayCategory>>(emptyList())
        private set
    var category by mutableStateOf<EbayCategory?>(null)
        private set
    var aspects by mutableStateOf<List<EbayAspect>>(emptyList())
        private set
    val aspectValues = mutableStateMapOf<String, String>()
    var conditions by mutableStateOf<List<EbayCondition>>(emptyList())
        private set
    var condition by mutableStateOf<EbayCondition?>(null)
    var policies by mutableStateOf<EbayPolicies?>(null)
        private set
    var fulfillmentId by mutableStateOf("")
    var paymentId by mutableStateOf("")
    var returnId by mutableStateOf("")
    var postalCode by mutableStateOf(settings.postalCode)
    var city by mutableStateOf(settings.city)

    private var draftCondition = ""
    private var preparedFor: String? = null

    /** Füllt die Angaben aus dem Entwurf vor und lädt Vorschläge von eBay. */
    fun prepare(listingKey: String, draft: ListingDraft, previous: EbayPublishResult? = null) {
        if (preparedFor == listingKey && error == null) return
        preparedFor = listingKey
        client = null
        result = previous
        error = null
        title = EbayMapping.title(draft)
        priceEur = EbayMapping.price(draft)
        bestOffer = EbayMapping.bestOffer(draft)
        draftCondition = draft.condition
        categoryQuery = draft.title
        category = null
        categories = emptyList()
        aspects = emptyList()
        aspectValues.clear()
        conditions = emptyList()
        condition = null
        launchTask("Vorschläge von eBay werden geladen …") {
            val loadedPolicies = client().policies()
            withContext(Dispatchers.Main) {
                policies = loadedPolicies
                fulfillmentId = pick(loadedPolicies.fulfillment.map { it.id }, settings.policyId("fulfillment"))
                paymentId = pick(loadedPolicies.payment.map { it.id }, settings.policyId("payment"))
                returnId = pick(loadedPolicies.returns.map { it.id }, settings.policyId("return"))
            }
            searchNow(draft.title)
        }
    }

    private fun pick(ids: List<String>, remembered: String) = if (remembered in ids) remembered else ids.firstOrNull().orEmpty()

    fun searchCategories() = launchTask("Kategorien werden gesucht …") { searchNow(categoryQuery) }

    private suspend fun searchNow(query: String) {
        val found = client().suggestCategories(query.ifBlank { title })
        withContext(Dispatchers.Main) { categories = found }
        found.firstOrNull()?.let { loadCategory(it) }
    }

    fun selectCategory(c: EbayCategory) = launchTask("Merkmale der Kategorie werden geladen …") { loadCategory(c) }

    private suspend fun loadCategory(c: EbayCategory) {
        val loadedAspects = client().aspects(c.id)
        val loadedConditions = runCatching { client().conditions(c.id) }.getOrDefault(emptyList())
        withContext(Dispatchers.Main) {
            category = c
            aspects = loadedAspects.sortedByDescending { it.required }
            aspectValues.keys.retainAll(loadedAspects.map { it.name }.toSet())
            conditions = loadedConditions
            condition = EbayMapping.pickCondition(draftCondition, loadedConditions)
        }
    }

    val missingRequired: List<String>
        get() = aspects.filter { it.required && aspectValues[it.name].isNullOrBlank() }.map { it.name }

    val canPublish: Boolean
        get() = loggedIn && busy == null && category != null && title.isNotBlank() && priceEur >= 1 &&
            fulfillmentId.isNotBlank() && paymentId.isNotBlank() && returnId.isNotBlank() &&
            postalCode.filter(Char::isDigit).length == 5 && missingRequired.isEmpty()

    /** Lädt die Fotos hoch und veröffentlicht. [onPublished] speichert das Ergebnis bei der Anzeige. */
    fun publish(sku: String, draft: ListingDraft, photos: List<File>, onPublished: (EbayPublishResult) -> Unit) {
        val chosen = category ?: return
        settings.postalCode = postalCode
        settings.city = city
        settings.savePolicyId("fulfillment", fulfillmentId)
        settings.savePolicyId("payment", paymentId)
        settings.savePolicyId("return", returnId)
        launchTask("Fotos werden zu eBay hochgeladen …") {
            val c = client()
            val images = photos.take(MAX_PHOTOS).mapIndexed { i, file ->
                withContext(Dispatchers.Main) { busy = "Foto ${i + 1} von ${minOf(photos.size, MAX_PHOTOS)} wird hochgeladen …" }
                c.uploadImage(ImagePreparer.jpeg(file, PHOTO_EDGE), "foto_${i + 1}.jpg")
            }
            if (images.isEmpty()) throw EbayException("eBay verlangt mindestens ein Foto.")
            withContext(Dispatchers.Main) { busy = "Angebot wird bei eBay eingestellt …" }
            val location = c.ensureLocation(postalCode, city)
            val conditionEnum = condition?.enumName ?: EbayMapping.fallbackConditionEnum(draftCondition)
            val offer = EbayOffer(
                sku = sku,
                title = title.trim(),
                description = EbayMapping.descriptionHtml(draft.description),
                priceEur = priceEur,
                bestOffer = bestOffer,
                categoryId = chosen.id,
                conditionEnum = conditionEnum,
                conditionNote = if (draftCondition.isNotBlank()) "Zustand: $draftCondition" else "",
                aspects = aspectValues.toMap(),
                imageUrls = images,
                fulfillmentPolicyId = fulfillmentId,
                paymentPolicyId = paymentId,
                returnPolicyId = returnId,
                locationKey = location,
            )
            val published = c.publish(offer)
            withContext(Dispatchers.Main) {
                result = published
                onPublished(published)
            }
        }
    }

    private fun launchTask(message: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = message
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: EbayException) {
                error = e.message
            } catch (e: Exception) {
                error = "Unerwarteter Fehler: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    private companion object {
        /** eBay erlaubt in den meisten Kategorien 24 Fotos. */
        const val MAX_PHOTOS = 24
        const val PHOTO_EDGE = 1600
    }
}
