package dev.naominet.lazer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopCueSheetTest {
    @Test
    fun `parses BOM Unicode file name metadata and sheet performer inheritance`() {
        val sheet = parseDesktopCueSheet(
            "\uFEFF" + """
                REM GENRE Classical
                TITLE "专辑 标题"
                PERFORMER "唱片艺人"
                FILE "音乐目录/交响乐.flac" FLAC
                TRACK 01 AUDIO
                TITLE "第一乐章"
                PERFORMER "独奏者"
                INDEX 01 00:00:00
                TRACK 02 AUDIO
                INDEX 01 03:12:24
            """.trimIndent(),
        )

        assertNotNull(sheet)
        assertEquals(
            listOf(DesktopCueSourceFile("音乐目录/交响乐.flac", "FLAC")),
            sheet?.files,
        )
        assertEquals("专辑 标题", sheet?.title)
        assertEquals("唱片艺人", sheet?.performer)
        assertEquals(
            listOf(
                DesktopCueTrack(1, "第一乐章", "独奏者", 0L, fileIndex = 0),
                DesktopCueTrack(2, null, "唱片艺人", 14_424L, fileIndex = 0),
            ),
            sheet?.tracks,
        )
    }

    @Test
    fun `accepts WAVE and FLAC file types and preserves exact 75 Hz frame positions`() {
        val wave = parseDesktopCueSheet(twoTrackCue(file = "album.wav", type = "WAVE", secondIndex = "00:01:00"))
        val flac = parseDesktopCueSheet(twoTrackCue(file = "album.flac", type = "FLAC", secondIndex = "01:00:00"))

        assertEquals(listOf(DesktopCueSourceFile("album.wav", "WAVE")), wave?.files)
        assertEquals(75L, wave?.tracks?.getOrNull(1)?.index01CueFrames)
        assertEquals(4_500L, flac?.tracks?.getOrNull(1)?.index01CueFrames)
    }

    @Test
    fun `parses multiple files with per file timestamps and correct track binding`() {
        val sheet = parseDesktopCueSheet(
            """
                FILE "first.wav" WAVE
                TRACK 01 AUDIO
                INDEX 01 00:00:00
                TRACK 02 AUDIO
                INDEX 01 03:12:24
                FILE "second.wav" WAVE
                TRACK 03 AUDIO
                INDEX 01 00:00:00
                TRACK 04 AUDIO
                INDEX 01 00:45:10
            """,
        )
        assertNotNull(sheet)
        assertEquals(
            listOf(
                DesktopCueSourceFile("first.wav", "WAVE"),
                DesktopCueSourceFile("second.wav", "WAVE"),
            ),
            sheet?.files,
        )
        assertEquals(listOf(0, 0, 1, 1), sheet?.tracks?.map(DesktopCueTrack::fileIndex))
        assertEquals(
            listOf(0L, 14_424L, 0L, 3_385L),
            sheet?.tracks?.map(DesktopCueTrack::index01CueFrames),
        )
    }

    @Test
    fun `rejects unsupported types empty paths control characters and empty file blocks`() {
        assertRejected(cue(file = "disc.bin", type = "BINARY"))
        assertRejected(cue(file = "disc.aiff", type = "AIFF"))
        assertRejected(cue(file = "   "))
        assertRejected(cue(file = "bad\u0001name.wav"))
        assertRejected(
            """
                FILE "empty.wav" WAVE
                FILE "used.wav" WAVE
                TRACK 01 AUDIO
                INDEX 01 00:00:00
            """,
        )
        assertRejected(
            """
                FILE "used.wav" WAVE
                TRACK 01 AUDIO
                INDEX 01 00:00:00
                FILE "empty.wav" WAVE
            """,
        )
    }

    @Test
    fun `rejects data tracks and non ascending or duplicate track numbers`() {
        assertRejected(cue(trackHeader = "TRACK 01 MODE1/2352"))
        assertRejected(
            """
                FILE "album.wav" WAVE
                TRACK 02 AUDIO
                INDEX 01 00:00:00
                TRACK 01 AUDIO
                INDEX 01 00:01:00
            """,
        )
        assertRejected(
            """
                FILE "album.wav" WAVE
                TRACK 01 AUDIO
                INDEX 01 00:00:00
                TRACK 01 AUDIO
                INDEX 01 00:01:00
            """,
        )
    }

    @Test
    fun `rejects pregap postgap flags and indexes other than INDEX 01`() {
        listOf(
            "PREGAP 00:02:00",
            "POSTGAP 00:02:00",
            "FLAGS DCP",
            "INDEX 00 00:00:00",
            "INDEX 02 00:00:00",
        ).forEach { unsupported ->
            assertRejected(
                """
                    FILE "album.wav" WAVE
                    TRACK 01 AUDIO
                    $unsupported
                    INDEX 01 00:00:00
                """,
            )
        }
    }

    @Test
    fun `rejects malformed times and minute second or frame overflow`() {
        listOf(
            "0:00:00",
            "00:60:00",
            "00:00:75",
            "00:00:0x",
            "00:00",
            "999999999999999999999999:00:00",
        ).forEach { time ->
            assertRejected(cue(index = time))
        }
    }

    @Test
    fun `rejects missing duplicate and nonmonotonic INDEX 01 positions`() {
        assertRejected(
            """
                FILE "album.wav" WAVE
                TRACK 01 AUDIO
            """,
        )
        assertRejected(
            """
                FILE "album.wav" WAVE
                TRACK 01 AUDIO
                INDEX 01 00:00:00
                INDEX 01 00:00:01
            """,
        )
        assertRejected(
            """
                FILE "album.wav" WAVE
                TRACK 01 AUDIO
                INDEX 01 00:01:00
                TRACK 02 AUDIO
                INDEX 01 00:01:00
            """,
        )
        assertRejected(
            """
                FILE "album.wav" WAVE
                TRACK 01 AUDIO
                INDEX 01 00:02:00
                TRACK 02 AUDIO
                INDEX 01 00:01:00
            """,
        )
    }

    @Test
    fun `rejects duplicate sheet and track title or performer metadata`() {
        assertRejected(
            """
                TITLE "Album one"
                TITLE "Album two"
                FILE "album.wav" WAVE
                TRACK 01 AUDIO
                INDEX 01 00:00:00
            """,
        )
        assertRejected(
            """
                FILE "album.wav" WAVE
                TRACK 01 AUDIO
                PERFORMER "Artist one"
                PERFORMER "Artist two"
                INDEX 01 00:00:00
            """,
        )
    }

    @Test
    fun `ignores REM metadata but rejects unknown directives and malformed quoting`() {
        val accepted = parseDesktopCueSheet(
            """
                REM DATE 2026
                REM REPLAYGAIN_ALBUM_GAIN -5 dB
                FILE "album.wav" WAVE
                TRACK 01 AUDIO
                INDEX 01 00:00:00
            """,
        )
        assertNotNull(accepted)
        assertRejected(cue(extraDirective = "SOMETHING 00:00:00"))
        assertRejected(cue(file = "\"unclosed.wav", type = "WAVE"))
        assertRejected(cue(title = "\"unclosed title"))
    }

    @Test
    fun `requires at least one file and one audio track per file`() {
        assertNull(parseDesktopCueSheet("TITLE \"No file\""))
        assertRejected("FILE \"empty.wav\" WAVE")
    }

    private fun cue(
        file: String = "album.wav",
        type: String = "WAVE",
        trackHeader: String = "TRACK 01 AUDIO",
        index: String = "00:00:00",
        title: String? = null,
        extraDirective: String? = null,
    ): String = buildString {
        appendLine("FILE \"$file\" $type")
        appendLine(trackHeader)
        if (title != null) appendLine("TITLE $title")
        if (extraDirective != null) appendLine(extraDirective)
        appendLine("INDEX 01 $index")
    }

    private fun twoTrackCue(file: String, type: String, secondIndex: String): String =
        """
            FILE "$file" $type
            TRACK 01 AUDIO
            INDEX 01 00:00:00
            TRACK 02 AUDIO
            INDEX 01 $secondIndex
        """.trimIndent()

    private fun assertRejected(text: String) {
        assertTrue("Expected CUE rejection:\n$text", parseDesktopCueSheet(text) == null)
    }
}
