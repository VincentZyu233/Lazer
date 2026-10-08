package dev.naominet.lazer

import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AndroidLocalArtworkStoreTest {
    @Test
    fun embeddedPngIsValidatedDeduplicatedAndStoredAsStableFileUri() {
        val bytes = pngBytes()

        val first = persistAndroidEmbeddedArtwork(RuntimeEnvironment.getApplication(), bytes)
        val second = persistAndroidEmbeddedArtwork(RuntimeEnvironment.getApplication(), bytes)

        assertNotNull(first)
        assertEquals(first, second)
        assertTrue(first.startsWith("file://"))
        val file = File(Uri.parse(first).path!!)
        assertTrue(file.isFile)
        assertEquals(bytes.toList(), file.readBytes().toList())
        assertTrue(file.canonicalPath.startsWith(
            File(RuntimeEnvironment.getApplication().filesDir, "local-artwork").canonicalPath,
        ))
        file.delete()
    }

    @Test
    fun invalidAndOversizedEmbeddedImagesAreIgnored() {
        val invalidPng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32)
        val oversized = ByteArray(AndroidLocalArtworkStore.MAX_IMAGE_BYTES + 1)

        assertNull(persistAndroidEmbeddedArtwork(RuntimeEnvironment.getApplication(), invalidPng))
        assertNull(persistAndroidEmbeddedArtwork(RuntimeEnvironment.getApplication(), oversized))
        assertFalse(File(RuntimeEnvironment.getApplication().filesDir, "local-artwork")
            .listFiles().orEmpty().any { it.length() == oversized.size.toLong() })
    }

    private fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
