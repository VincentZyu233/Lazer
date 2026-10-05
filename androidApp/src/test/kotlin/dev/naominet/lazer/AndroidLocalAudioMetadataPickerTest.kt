package dev.naominet.lazer

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AndroidLocalAudioMetadataPickerTest {
    @Test
    fun embeddedWavId3DisplayMetadataReplacesSystemOrFilenameFallback() {
        val file = File.createTempFile("lazer-embedded-tags-", ".wav")
        try {
            file.writeBytes(wavWithId3())

            val picked = readAndroidLocalAudioFile(
                RuntimeEnvironment.getApplication(),
                Uri.fromFile(file),
            )

            assertNotNull(picked)
            assertEquals("Embedded title", picked.title)
            assertEquals("Embedded artist", picked.artist)
            assertEquals("Embedded album", picked.album)
            assertEquals(LazerReplayGainTags(trackGainDb = -6.0), picked.replayGain)
        } finally {
            file.delete()
        }
    }

    private fun wavWithId3(): ByteArray {
        val id3Body = frame("TIT2", text("Embedded title")) +
            frame("TPE1", text("Embedded artist")) +
            frame("TALB", text("Embedded album")) +
            frame("TXXX", byteArrayOf(3) + "REPLAYGAIN_TRACK_GAIN".toByteArray() + byteArrayOf(0) +
                "-6.0 dB".toByteArray())
        val id3 = "ID3".bytes() + byteArrayOf(3, 0, 0) + synchsafe(id3Body.size) + id3Body
        val fmt = shortLe(1) + shortLe(2) + intLe(44_100) + intLe(176_400) +
            shortLe(4) + shortLe(16)
        val waveBody = "WAVE".bytes() + chunk("fmt ", fmt) + chunk("ID3 ", id3) +
            chunk("data", byteArrayOf(0, 0, 0, 0))
        return "RIFF".bytes() + intLe(waveBody.size) + waveBody
    }

    private fun frame(id: String, payload: ByteArray): ByteArray =
        id.bytes() + intBe(payload.size) + byteArrayOf(0, 0) + payload

    private fun text(value: String): ByteArray = byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)

    private fun chunk(id: String, payload: ByteArray): ByteArray =
        id.bytes() + intLe(payload.size) + payload + if (payload.size % 2 == 0) byteArrayOf() else byteArrayOf(0)

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7f).toByte(),
        ((value ushr 14) and 0x7f).toByte(),
        ((value ushr 7) and 0x7f).toByte(),
        (value and 0x7f).toByte(),
    )

    private fun shortLe(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())

    private fun intLe(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun intBe(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun String.bytes(): ByteArray = toByteArray(Charsets.US_ASCII)
}
