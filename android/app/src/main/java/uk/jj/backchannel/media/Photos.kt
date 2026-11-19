package uk.jj.backchannel.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Gets a photo ready to send: turned the right way up, shrunk, and written out again as a
 * fresh JPEG. Writing it out fresh is what removes the location, camera details and
 * everything else a phone hides inside a photo, because only the picture itself is kept.
 */
object Photos {
    class Prepared(val jpeg: ByteArray, val width: Int, val height: Int)

    /** Longest side of a sent photo, in pixels. */
    const val MAX_EDGE = 1600

    /** Sent photos are squeezed under this many bytes so they cross Tor in reasonable time. */
    const val TARGET_BYTES = 700_000

    private const val MAX_SOURCE_BYTES = 60 * 1024 * 1024

    @Throws(IOException::class)
    fun prepare(context: Context, uri: Uri): Prepared {
        val source = context.contentResolver.openInputStream(uri)?.use { readAll(it) }
            ?: throw IOException("could not open the photo")
        return prepare(source)
    }

    @Throws(IOException::class)
    fun prepare(source: ByteArray): Prepared {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("that file is not a picture")

        // Decode at a reduced size straight away so a 50-megapixel photo doesn't exhaust memory.
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, opts) ?: throw IOException("could not read the picture")

        bitmap = upright(bitmap, orientation(source))
        var edge = MAX_EDGE
        var quality = 82
        while (true) {
            val scaled = fit(bitmap, edge)
            val jpeg = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            if (jpeg.size <= TARGET_BYTES || (quality <= 55 && edge <= 640)) {
                return Prepared(jpeg, scaled.width, scaled.height)
            }
            // Still too big: lower the quality first, then the size.
            if (quality > 55) quality -= 9 else edge = (edge * 0.8f).roundToInt()
        }
    }

    /** A smaller copy for showing in the chat. */
    fun thumbnail(jpeg: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** A small square picture for a contact, cut from the middle of whatever was supplied. */
    fun avatar(source: Bitmap, size: Int = 160): ByteArray {
        val side = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled = if (side > size) Bitmap.createScaledBitmap(square, size, size, true) else square
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    private fun fit(b: Bitmap, edge: Int): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= edge) return b
        val f = edge.toFloat() / longest
        return Bitmap.createScaledBitmap(b, max(1, (b.width * f).roundToInt()), max(1, (b.height * f).roundToInt()), true)
    }

    private fun orientation(source: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(source)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun upright(b: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun readAll(inp: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = inp.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_SOURCE_BYTES) throw IOException("that photo is too large")
        }
        return out.toByteArray()
    }
}

/** Temporary files (a photo just taken, a voice note being recorded) are overwritten before deletion. */
object Scratch {
    fun dir(context: Context, name: String): File = File(context.cacheDir, name).apply { mkdirs() }

    fun wipe(f: File?) {
        if (f == null || !f.exists()) return
        try {
            val len = f.length()
            f.outputStream().use { out ->
                val zeros = ByteArray(64 * 1024)
                var left = len
                while (left > 0) {
                    val n = minOf(left, zeros.size.toLong()).toInt()
                    out.write(zeros, 0, n)
                    left -= n
                }
                out.fd.sync()
            }
        } catch (_: IOException) {
        }
        f.delete()
    }

    /** Clears anything left behind if the app was closed part-way through. */
    fun clear(context: Context) {
        for (name in listOf("capture", "voice")) {
            File(context.cacheDir, name).listFiles()?.forEach { wipe(it) }
        }
    }
}
