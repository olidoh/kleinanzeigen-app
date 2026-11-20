package de.olidoh.inserathelfer

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.olidoh.inserathelfer.core.ListingDraft
import de.olidoh.inserathelfer.core.ListingException
import de.olidoh.inserathelfer.core.ListingGenerator
import de.olidoh.inserathelfer.data.ApiKeyStore
import de.olidoh.inserathelfer.image.ImagePreparer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { SETTINGS, CAPTURE, DETAILS, REVIEW }

class ListingViewModel(app: Application) : AndroidViewModel(app) {

    private val keyStore = ApiKeyStore(app)

    var apiKey by mutableStateOf(keyStore.load())
        private set

    var screen by mutableStateOf(if (apiKey == null) Screen.SETTINGS else Screen.CAPTURE)

    val photos = mutableStateListOf<File>()
    var sellerNotes by mutableStateOf("")
    var draft by mutableStateOf<ListingDraft?>(null)
    var isGenerating by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    fun saveApiKey(key: String) {
        keyStore.save(key)
        apiKey = key.trim()
        screen = Screen.CAPTURE
    }

    fun removeApiKey() {
        keyStore.clear()
        apiKey = null
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
        val key = apiKey ?: run {
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
                    ListingGenerator(key).generate(jpegs, notes)
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
