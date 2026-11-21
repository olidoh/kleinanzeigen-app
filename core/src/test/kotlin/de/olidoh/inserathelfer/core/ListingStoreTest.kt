package de.olidoh.inserathelfer.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListingStoreTest {

    @TempDir
    lateinit var tmp: File

    private val draft = ListingDraft("Fahrrad 28 Zoll", "Gut erhalten.", 120, PriceType.NEGOTIABLE, "Fahrräder", "Gut", "Vergleichsangebote")

    private fun photo(name: String, bytes: String) = File(tmp, name).apply { writeText(bytes) }

    @Test
    fun `speichert Anzeige mit kopierten Fotos und liest sie wieder`() {
        val store = ListingStore(File(tmp, "store")) { 1000L }
        val src = listOf(photo("a.jpg", "A"), photo("b.jpg", "B"))
        val saved = store.create(draft, src, "Notiz")
        src.forEach { it.delete() }

        val loaded = ListingStore(File(tmp, "store")).list().single()
        assertEquals(saved.id, loaded.id)
        assertEquals(draft, loaded.draft)
        assertEquals("Notiz", loaded.sellerNotes)
        assertEquals(1000L, loaded.createdAt)
        assertEquals(listOf("A", "B"), loaded.photos.map { it.readText() })
        assertFalse(loaded.isPublished)
    }

    @Test
    fun `sortiert neueste zuerst und aktualisiert Text und Status`() {
        var now = 1L
        val store = ListingStore(File(tmp, "store")) { now }
        val first = store.create(draft, emptyList())
        now = 2L
        val second = store.create(draft.copy(title = "Zweite"), emptyList())
        assertEquals(listOf(second.id, first.id), store.list().map { it.id })

        store.update(first.copy(draft = first.draft.copy(priceEur = 99)))
        now = 5L
        store.markPublished(store.get(first.id)!!)
        val reloaded = store.get(first.id)!!
        assertEquals(99, reloaded.draft.priceEur)
        assertEquals(5L, reloaded.publishedAt)
        assertNull(store.markPublished(reloaded, published = false).publishedAt)
        assertFalse(store.get(first.id)!!.isPublished)
    }

    @Test
    fun `loescht Anzeige samt Fotos und ignoriert kaputte Eintraege`() {
        val root = File(tmp, "store")
        val store = ListingStore(root)
        val saved = store.create(draft, listOf(photo("a.jpg", "A")))
        File(root, "kaputt").apply { mkdirs(); File(this, "listing.json").writeText("{nicht json") }
        assertEquals(1, store.list().size)

        store.delete(saved.id)
        assertTrue(store.list().isEmpty())
        assertFalse(File(root, saved.id).exists())
        // Update nach dem Löschen legt nichts neu an
        store.update(saved)
        assertFalse(File(root, saved.id).exists())
    }
}
