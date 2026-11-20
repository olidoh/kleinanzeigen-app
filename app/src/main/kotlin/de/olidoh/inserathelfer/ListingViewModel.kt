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
import de.olidoh.inserathelfer.data.AiSettings
import de.olidoh.inserathelfer.image.ImagePreparer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { SETTINGS, CAPTURE, DETAILS, REVIEW, POST }

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

    /** Speichert die Einstellungen eines Anbieters und macht ihn zum aktiven. Leerer Schlüssel: alten behalten. */
    fun saveProvider(provider: AiProvider, apiKey: String, model: String, baseUrl: String) {
        settings.save(provider, apiKey.ifBlank { null }, model, baseUrl)
        aiConfig = settings.currentConfig()
        if (aiConfig != null) screen = Screen.CAPTURE
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

    fun removePhoto(file: File) {
        photos.remove(file)
        file.delete()
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
        viewModelScope.launch {
            try {
                draft = withContext(Dispatchers.IO) {
                    val jpegs = images.take(ListingGenerator.MAX_IMAGES).map(ImagePreparer::jpegForAi)
                    ListingGenerator.create(config).generate(jpegs, notes)
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
        draft = draft?.let(transform)
    }

    /** Neue Anzeige beginnen: Fotos und Entwurf verwerfen. */
    fun startOver() {
        ImagePreparer.photoDir(getApplication()).listFiles()?.forEach { it.delete() }
        photos.clear()
        sellerNotes = ""
        draft = null
        error = null
        screen = Screen.CAPTURE
    }
}
