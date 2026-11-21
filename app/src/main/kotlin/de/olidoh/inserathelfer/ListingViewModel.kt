package de.olidoh.inserathelfer

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.olidoh.inserathelfer.core.AiConfig
import de.olidoh.inserathelfer.core.AiProvider
import de.olidoh.inserathelfer.core.ListingDraft
import de.olidoh.inserathelfer.core.ListingException
import de.olidoh.inserathelfer.core.ListingGenerator
import de.olidoh.inserathelfer.core.ListingStore
import de.olidoh.inserathelfer.core.SavedListing
import de.olidoh.inserathelfer.core.EbayPublication
import de.olidoh.inserathelfer.core.ebay.EbayPublishResult
import de.olidoh.inserathelfer.data.AiSettings
import de.olidoh.inserathelfer.data.EbaySettings
import de.olidoh.inserathelfer.data.Platform
import de.olidoh.inserathelfer.ebay.EbayFlow
import de.olidoh.inserathelfer.image.ImagePreparer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

enum class Screen { SETTINGS, CAPTURE, DETAILS, REVIEW, POST, HISTORY, EBAY_LOGIN, EBAY_POST }

class ListingViewModel(app: Application) : AndroidViewModel(app) {

    val settings = AiSettings(app)

    /** Konfiguration des gewählten Anbieters; null, solange kein Schlüssel hinterlegt ist. */
    var aiConfig by mutableStateOf<AiConfig?>(settings.currentConfig())
        private set

    var screen by mutableStateOf(if (aiConfig == null) Screen.SETTINGS else Screen.CAPTURE)

    val photos = mutableStateListOf<File>()
    var sellerNotes by mutableStateOf("")
    var draft by mutableStateOf<ListingDraft?>(null)
    var isGenerating by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    /** Dauerhafter Speicher für erstellte Anzeigen (Fotos und Text). */
    private val store = ListingStore(File(app.filesDir, "listings"))

    /** Schreibzugriffe nacheinander ausführen, damit sich schnelle Änderungen nicht überholen. */
    private val storeDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /** Gespeicherte Anzeige, die gerade bearbeitet wird; null, solange noch nichts erzeugt wurde. */
    var current by mutableStateOf<SavedListing?>(null)
        private set
    var savedListings by mutableStateOf<List<SavedListing>>(emptyList())
        private set

    /** eBay-Anmeldung und -Einstellen. */
    val ebay = EbayFlow(EbaySettings(app), viewModelScope)

    /** Plattform, auf der eingestellt wird; die letzte Wahl bleibt gespeichert. */
    var platform by mutableStateOf(ebay.settings.platform)
        private set

    fun choosePlatform(p: Platform) {
        platform = p
        ebay.settings.platform = p
    }

    /** Wohin es nach der eBay-Anmeldung weitergeht. */
    var afterEbayLogin = Screen.EBAY_POST
        private set

    /** „Einstellen“ auf der gewählten Plattform. */
    fun startPosting() {
        when (platform) {
            Platform.KLEINANZEIGEN -> screen = Screen.POST
            Platform.EBAY -> when {
                !ebay.isConfigured -> error = "Die eBay-Anbindung ist in dieser App-Version noch nicht eingerichtet " +
                    "(siehe Einstellungen > eBay)."
                !ebay.loggedIn -> openEbayLogin(Screen.EBAY_POST)
                else -> openEbayPost()
            }
        }
    }

    fun openEbayLogin(then: Screen) {
        afterEbayLogin = then
        screen = Screen.EBAY_LOGIN
    }

    fun onEbayLoginFinished(success: Boolean) {
        val target = afterEbayLogin
        if (success && target == Screen.EBAY_POST) openEbayPost() else screen = if (target == Screen.EBAY_POST) Screen.REVIEW else target
    }

    private fun openEbayPost() {
        val d = draft ?: return
        val saved = current?.ebay?.let { EbayPublishResult(it.listingId, it.url, emptyList()) }
        ebay.prepare(current?.id ?: "neu", d, saved)
        screen = Screen.EBAY_POST
    }

    /** Eindeutige Artikelnummer für eBay; gleich für dieselbe gespeicherte Anzeige. */
    val ebaySku: String get() = current?.id ?: fallbackSku
    private val fallbackSku = java.util.UUID.randomUUID().toString()

    fun onEbayPublished(result: EbayPublishResult) {
        val publication = EbayPublication(result.listingId, result.url, System.currentTimeMillis())
        current = current?.copy(ebay = publication)?.also(::persist)
    }

    /** Speichert die Einstellungen eines Anbieters und macht ihn zum aktiven. Leerer Schlüssel: alten behalten. */
    fun saveProvider(provider: AiProvider, apiKey: String, model: String, baseUrl: String) {
        settings.save(provider, apiKey.ifBlank { null }, model, baseUrl)
        aiConfig = settings.currentConfig()
        if (aiConfig != null) screen = Screen.CAPTURE
    }

    /** Dauerhafte Vorgabe zu Stil und Tonalität (z. B. „immer in der Du-Form“). */
    var styleGuide by mutableStateOf(settings.styleGuide)
        private set

    fun saveStyleGuide(text: String) {
        settings.styleGuide = text
        styleGuide = settings.styleGuide
    }

    fun removeKey(provider: AiProvider) {
        settings.removeKey(provider)
        aiConfig = settings.currentConfig()
    }

    fun addPhoto(file: File) {
        photos.add(file)
    }

    fun importPhotos(uris: List<Uri>) {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { runCatching { ImagePreparer.importFromUri(getApplication(), it) }.getOrNull() }
            }
            photos.addAll(files)
        }
    }

    /** Macht [file] zum Titelbild: Es steht vorne und wird bei Kleinanzeigen als erstes Bild hochgeladen. */
    fun setCoverPhoto(file: File) {
        val index = photos.indexOf(file)
        if (index <= 0) return
        photos.removeAt(index)
        photos.add(0, file)
        current = current?.copy(photos = photos.toList())?.also(::persist)
    }

    fun removePhoto(file: File) {
        photos.remove(file)
        // Fotos einer gespeicherten Anzeige bleiben im Speicher, nur Kamera-Fotos werden gelöscht.
        if (file.parentFile == ImagePreparer.photoDir(getApplication())) file.delete()
    }

    fun generate() {
        val config = aiConfig ?: run {
            screen = Screen.SETTINGS
            return
        }
        if (photos.isEmpty() || isGenerating) return
        isGenerating = true
        error = null
        val images = photos.toList()
        val notes = sellerNotes
        val style = styleGuide
        viewModelScope.launch {
            try {
                val generated = withContext(Dispatchers.IO) {
                    val jpegs = images.take(ListingGenerator.MAX_IMAGES).map(ImagePreparer::jpegForAi)
                    ListingGenerator.create(config).generate(jpegs, notes, style)
                }
                draft = generated
                openedFromHistory = false
                current = withContext(storeDispatcher) {
                    val existing = current
                    if (existing != null && existing.photos == images) {
                        existing.copy(draft = generated, sellerNotes = notes).also(store::update)
                    } else {
                        runCatching { store.create(generated, images, notes) }.getOrNull()
                    }
                }
                // Ab jetzt mit den gespeicherten Kopien arbeiten, damit Titelbild-Wahl und Reihenfolge erhalten bleiben.
                current?.photos?.takeIf { it.size == images.size && photos.toList() == images }?.let {
                    photos.clear()
                    photos.addAll(it)
                }
                screen = Screen.REVIEW
            } catch (e: ListingException) {
                error = e.message
            } catch (e: Exception) {
                error = "Unerwarteter Fehler: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                isGenerating = false
            }
        }
    }

    fun updateDraft(transform: (ListingDraft) -> ListingDraft) {
        val updated = draft?.let(transform) ?: return
        draft = updated
        current = current?.copy(draft = updated)?.also(::persist)
    }

    private fun persist(listing: SavedListing) {
        viewModelScope.launch(storeDispatcher) { runCatching { store.update(listing) } }
    }

    /** Markiert die aktuelle Anzeige als veröffentlicht (oder nimmt die Markierung zurück). */
    fun setPublished(published: Boolean) {
        val listing = current ?: return
        current = listing.copy(publishedAt = if (published) System.currentTimeMillis() else null).also(::persist)
    }

    /** Zurück aus der Prüfansicht: zu „Meine Anzeigen“, wenn die Anzeige von dort geöffnet wurde. */
    fun leaveReview() {
        screen = if (openedFromHistory) Screen.HISTORY else Screen.DETAILS
        if (openedFromHistory) refreshHistory()
    }

    private var openedFromHistory = false

    fun showHistory() {
        screen = Screen.HISTORY
        refreshHistory()
    }

    fun refreshHistory() {
        viewModelScope.launch {
            savedListings = withContext(storeDispatcher) { runCatching { store.list() }.getOrDefault(emptyList()) }
        }
    }

    /** Öffnet eine gespeicherte Anzeige, um sie anzusehen, zu ändern oder zu veröffentlichen. */
    fun openSaved(listing: SavedListing) {
        clearCachePhotos()
        photos.clear()
        photos.addAll(listing.photos)
        sellerNotes = listing.sellerNotes
        draft = listing.draft
        current = listing
        error = null
        openedFromHistory = true
        screen = Screen.REVIEW
    }

    fun deleteSaved(listing: SavedListing) {
        if (current?.id == listing.id) {
            current = null
            photos.clear()
            draft = null
        }
        savedListings = savedListings.filterNot { it.id == listing.id }
        viewModelScope.launch(storeDispatcher) { runCatching { store.delete(listing.id) } }
    }

    private fun clearCachePhotos() {
        ImagePreparer.photoDir(getApplication()).listFiles()?.forEach { it.delete() }
    }

    /** Neue Anzeige beginnen. Gespeicherte Anzeigen bleiben unter „Meine Anzeigen“ erhalten. */
    fun startOver() {
        clearCachePhotos()
        photos.clear()
        current = null
        openedFromHistory = false
        sellerNotes = ""
        draft = null
        error = null
        screen = Screen.CAPTURE
    }
}
