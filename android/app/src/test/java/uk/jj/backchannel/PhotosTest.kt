package uk.jj.backchannel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uk.jj.backchannel.media.Photos
import uk.jj.backchannel.media.Scratch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Random

/** Checks that a photo is shrunk, turned the right way up, and loses its hidden details before sending. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class PhotosTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    /** A big, detailed picture like a phone camera would produce: left half red, right half blue, with noise. */
    private fun cameraPhoto(width: Int, height: Int): ByteArray {
        val rnd = Random(5)
        val px = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val n = rnd.nextInt(60)
                px[y * width + x] = if (x < width / 2) Color.rgb(195 + n, n, n) else Color.rgb(n, n, 195 + n)
            }
        }
        val bmp = Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    /** Adds what a real camera adds: where it was taken, what took it, when, and which way up. */
    private fun withHiddenDetails(jpeg: ByteArray, orientation: Int): ByteArray {
        val f = File(context.cacheDir, "exif-test.jpg")
        f.writeBytes(jpeg)
        ExifInterface(f.absolutePath).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "53/1,28/1,4500/100")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "2/1,17/1,3000/100")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            setAttribute(ExifInterface.TAG_MAKE, "samsung")
            setAttribute(ExifInterface.TAG_MODEL, "SM-S911B")
            setAttribute(ExifInterface.TAG_DATETIME, "2026:11:19 07:42:10")
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return f.readBytes().also { f.delete() }
    }

    @Test fun aCameraPhotoIsShrunkTurnedUprightAndStrippedOfHiddenDetails() {
        val original = withHiddenDetails(cameraPhoto(4000, 3000), ExifInterface.ORIENTATION_ROTATE_90)
        // First prove the test photo really carries the details, or the check below means nothing.
        val before = ExifInterface(ByteArrayInputStream(original))
        assertNotNull("test photo should carry a location", before.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertTrue(before.getLatLong(FloatArray(2)))
        assertEquals("SM-S911B", before.getAttribute(ExifInterface.TAG_MODEL))
        assertTrue("test photo should be large: ${original.size}", original.size > 1_500_000)

        val p = Photos.prepare(original)

        assertTrue("sent photo must be under the size limit, was ${p.jpeg.size}", p.jpeg.size <= Photos.TARGET_BYTES)
        assertTrue(p.jpeg.size <= uk.jj.backchannel.core.Content.MAX_MEDIA)
        assertEquals("a sideways landscape photo comes out portrait", 1200, p.width)
        assertEquals(1600, p.height)
        val decoded = BitmapFactory.decodeByteArray(p.jpeg, 0, p.jpeg.size)
        assertEquals(1200, decoded.width)
        assertEquals(1600, decoded.height)
        // Turned 90 degrees clockwise, the red left half ends up at the top and the blue right half at the bottom.
        assertTrue("top should be red", Color.red(decoded.getPixel(600, 200)) > 150 && Color.blue(decoded.getPixel(600, 200)) < 110)
        assertTrue("bottom should be blue", Color.blue(decoded.getPixel(600, 1400)) > 150 && Color.red(decoded.getPixel(600, 1400)) < 110)

        val after = ExifInterface(ByteArrayInputStream(p.jpeg))
        for (tag in listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME,
        )) {
            assertNull("$tag must not survive", after.getAttribute(tag))
        }
        assertFalse("no location may be readable", after.getLatLong(FloatArray(2)))
        val text = String(p.jpeg, Charsets.ISO_8859_1)
        assertFalse(text.contains("SM-S911B") || text.contains("samsung") || text.contains("2026:11:19"))
    }

    @Test fun aSmallPictureIsNotBlownUp() {
        val p = Photos.prepare(cameraPhoto(640, 480))
        assertEquals(640, p.width)
        assertEquals(480, p.height)
    }

    @Test fun somethingThatIsNotAPictureIsRefused() {
        try {
            Photos.prepare("this is a text file, not a photo".toByteArray())
            fail("should have been refused")
        } catch (_: IOException) {
        }
    }

    @Test fun thumbnailsAndContactPicturesAreSmall() {
        val p = Photos.prepare(cameraPhoto(3000, 2000))
        val thumb = Photos.thumbnail(p.jpeg, 720)!!
        assertTrue(maxOf(thumb.width, thumb.height) in 400..1600)
        val avatar = Photos.avatar(BitmapFactory.decodeByteArray(p.jpeg, 0, p.jpeg.size))
        val a = BitmapFactory.decodeByteArray(avatar, 0, avatar.size)
        assertEquals(160, a.width)
        assertEquals(160, a.height)
        assertTrue("contact picture must be tiny, was ${avatar.size}", avatar.size < 40_000)
    }

    @Test fun scratchFilesAreOverwrittenAndRemoved() {
        val dir = Scratch.dir(context, "capture")
        val f = File(dir, "left-behind.jpg").apply { writeBytes(ByteArray(100_000) { 7 }) }
        val v = File(Scratch.dir(context, "voice"), "left-behind.m4a").apply { writeBytes(ByteArray(5_000) { 7 }) }
        Scratch.clear(context)
        assertFalse(f.exists())
        assertFalse(v.exists())
    }
}
