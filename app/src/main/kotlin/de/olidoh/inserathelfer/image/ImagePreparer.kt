package de.olidoh.inserathelfer.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max

object ImagePreparer {

    /** Größere Bilder bringen der KI nichts, kosten aber mehr Tokens. */
    private const val MAX_EDGE_FOR_AI = 1568

    /** Ordner für Fotos dieser Anzeige (per FileProvider teilbar). */
    fun photoDir(context: Context): File = File(context.cacheDir, "photos").apply { mkdirs() }

    fun newPhotoFile(context: Context): File = File(photoDir(context), "foto_${UUID.randomUUID()}.jpg")

    /** Kopiert ein Bild aus der Galerie in den App-Ordner, damit es geteilt werden kann. */
    fun importFromUri(context: Context, uri: Uri): File {
        val target = newPhotoFile(context)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Bild konnte nicht geöffnet werden." }
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    /** Verkleinertes, richtig gedrehtes JPEG für die KI. */
    fun jpegForAi(file: File): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE_FOR_AI) sample *= 2

        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Foto ${file.name} konnte nicht gelesen werden.")

        val longEdge = max(decoded.width, decoded.height)
        val scale = if (longEdge > MAX_EDGE_FOR_AI) MAX_EDGE_FOR_AI.toFloat() / longEdge else 1f
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotationDegrees(file).toFloat())
        }
        val prepared = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

        return ByteArrayOutputStream().use { out ->
            prepared.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (prepared !== decoded) prepared.recycle()
            decoded.recycle()
            out.toByteArray()
        }
    }

    private fun rotationDegrees(file: File): Int =
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
}
