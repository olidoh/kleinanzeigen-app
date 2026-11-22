package de.olidoh.inserathelfer.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.util.UUID

/** Eine gespeicherte Anzeige mit ihren Fotos, die später angesehen oder veröffentlicht werden kann. */
data class SavedListing(
    val id: String,
    val createdAt: Long,
    val draft: ListingDraft,
    val photos: List<File>,
    val sellerNotes: String = "",
    /** Zeitpunkt der Veröffentlichung bei Kleinanzeigen. */
    val publishedAt: Long? = null,
    /** Angebot bei eBay, falls die Anzeige dort eingestellt wurde. */
    val ebay: EbayPublication? = null,
) {
    val isPublished: Boolean get() = publishedAt != null
}

/** Ein bei eBay veröffentlichtes Angebot dieser Anzeige. */
data class EbayPublication(val listingId: String, val url: String, val publishedAt: Long)

/**
 * Legt Anzeigen dauerhaft auf dem Gerät ab: je Anzeige ein Ordner mit `listing.json` und den Fotos.
 * Die Fotos werden kopiert, damit sie auch nach „Neue Anzeige beginnen“ erhalten bleiben.
 */
class ListingStore(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {

    private val mapper = ObjectMapper()

    /** Speichert eine neue Anzeige und kopiert die Fotos in den Speicher. */
    fun create(draft: ListingDraft, sourcePhotos: List<File>, sellerNotes: String = ""): SavedListing {
        val id = UUID.randomUUID().toString()
        val photoDir = File(dir(id), PHOTO_DIR).apply { mkdirs() }
        val photos = sourcePhotos.filter(File::isFile).mapIndexed { i, src ->
            src.copyTo(File(photoDir, "foto_${i + 1}.jpg"), overwrite = true)
        }
        return SavedListing(id, clock(), draft, photos, sellerNotes).also(::write)
    }

    /** Überschreibt Text, Notizen und Status einer gespeicherten Anzeige (die Fotos bleiben). */
    fun update(listing: SavedListing) {
        if (dir(listing.id).isDirectory) write(listing)
    }

    fun markPublished(listing: SavedListing, published: Boolean = true): SavedListing =
        listing.copy(publishedAt = if (published) clock() else null).also(::update)

    /** Alle gespeicherten Anzeigen, neueste zuerst. Kaputte Einträge werden übersprungen. */
    fun list(): List<SavedListing> =
        root.listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { runCatching { read(it) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    fun get(id: String): SavedListing? = runCatching { read(dir(id)) }.getOrNull()

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    private fun dir(id: String): File {
        require(id.isNotBlank() && !id.contains('/') && !id.contains("..")) { "Ungültige ID: $id" }
        return File(root, id)
    }

    private fun write(listing: SavedListing) {
        val d = listing.draft
        val json = mapper.createObjectNode().apply {
            put("version", 1)
            put("id", listing.id)
            put("createdAt", listing.createdAt)
            listing.publishedAt?.let { put("publishedAt", it) }
            put("sellerNotes", listing.sellerNotes)
            listing.ebay?.let { e ->
                putObject("ebay").apply {
                    put("listingId", e.listingId)
                    put("url", e.url)
                    put("publishedAt", e.publishedAt)
                }
            }
            putArray("photos").apply { listing.photos.forEach { add(it.name) } }
            putObject("draft").apply {
                put("title", d.title)
                put("description", d.description)
                put("priceEur", d.priceEur)
                put("priceType", d.priceType.name)
                put("category", d.category)
                put("condition", d.condition)
                put("priceReasoning", d.priceReasoning)
            }
        }
        val dir = dir(listing.id).apply { mkdirs() }
        val tmp = File(dir, "$FILE.tmp")
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, json)
        if (!tmp.renameTo(File(dir, FILE))) {
            tmp.copyTo(File(dir, FILE), overwrite = true)
            tmp.delete()
        }
    }

    private fun read(dir: File): SavedListing {
        val json = mapper.readTree(File(dir, FILE)) as ObjectNode
        val d = json.get("draft")
        val photoDir = File(dir, PHOTO_DIR)
        return SavedListing(
            id = json.get("id").asText(),
            createdAt = json.get("createdAt").asLong(),
            publishedAt = json.get("publishedAt")?.takeIf { !it.isNull }?.asLong(),
            sellerNotes = json.path("sellerNotes").asText(""),
            ebay = json.get("ebay")?.takeIf { it.isObject && it.path("listingId").asText("").isNotBlank() }?.let {
                EbayPublication(it.path("listingId").asText(), it.path("url").asText(""), it.path("publishedAt").asLong(0))
            },
            photos = json.path("photos").map { File(photoDir, File(it.asText()).name) }.filter(File::isFile),
            draft = ListingDraft(
                title = d.path("title").asText(""),
                description = d.path("description").asText(""),
                priceEur = d.path("priceEur").asInt(0),
                priceType = runCatching { PriceType.valueOf(d.path("priceType").asText()) }.getOrDefault(PriceType.NEGOTIABLE),
                category = d.path("category").asText(""),
                condition = d.path("condition").asText(""),
                priceReasoning = d.path("priceReasoning").asText(""),
            ),
        )
    }

    private companion object {
        const val FILE = "listing.json"
        const val PHOTO_DIR = "photos"
    }
}
