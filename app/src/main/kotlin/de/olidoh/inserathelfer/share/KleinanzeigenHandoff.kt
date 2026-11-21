package de.olidoh.inserathelfer.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Übergibt eine Anzeige an die offizielle Kleinanzeigen-App, ohne deren Login anzufassen:
 * Text in die Zwischenablage, Fotos per Teilen oder über die Galerie.
 */
object KleinanzeigenHandoff {

    const val KLEINANZEIGEN_PACKAGE = "com.ebay.kleinanzeigen"

    enum class Result {
        /** Fotos wurden direkt an die Kleinanzeigen-App geteilt. */
        SHARED_TO_APP,

        /** App geöffnet, Fotos liegen in der Galerie bereit. */
        OPENED_APP_PHOTOS_IN_GALLERY,

        /** App nicht installiert: Play Store geöffnet, Text liegt in der Zwischenablage. */
        APP_NOT_INSTALLED,
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun handOff(context: Context, photos: List<File>, title: String, fullText: String): Result {
        copyToClipboard(context, "Anzeige", fullText)
        val pm = context.packageManager
        val uris = ArrayList(photos.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })

        val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, fullText)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val toKleinanzeigen = Intent(share).setPackage(KLEINANZEIGEN_PACKAGE)
        if (toKleinanzeigen.resolveActivity(pm) != null) {
            context.startActivity(toKleinanzeigen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return Result.SHARED_TO_APP
        }

        val launch = pm.getLaunchIntentForPackage(KLEINANZEIGEN_PACKAGE)
        if (launch != null) {
            // Die App nimmt keine geteilten Fotos an: in die Galerie legen und App öffnen.
            photos.forEach { saveToGallery(context, it) }
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return Result.OPENED_APP_PHOTOS_IN_GALLERY
        }

        openPlayStore(context)
        return Result.APP_NOT_INSTALLED
    }

    /** Legt ein Foto unter Bilder/Inserat-Helfer ab, damit es in jeder Bildauswahl erscheint. */
    fun saveToGallery(context: Context, photo: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, photo.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Inserat-Helfer")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> photo.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun openPlayStore(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$KLEINANZEIGEN_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (e: ActivityNotFoundException) {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$KLEINANZEIGEN_PACKAGE"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
