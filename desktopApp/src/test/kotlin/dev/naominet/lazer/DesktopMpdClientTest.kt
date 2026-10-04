package dev.naominet.lazer

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopMpdClientTest {
    @Test
    fun `reads status and repeated escaped song tags`() = runBlocking {
        val server = MpdTestServer(expectedConnections = 2) { command ->
            when (command) {
                "status" -> """
                    volume: 72
                    state: play
                    elapsed: 12.500
                    duration: 201.250
                    playlist: 17
                    playlistlength: 3
                    songid: 42
                    OK
                """.trimIndent()
                "currentsong" -> """
                    file: music/test.flac
                    Title: A\nB
                    Artist: First
                    Artist: Second
                    Album: Test album
                    OK
                """.trimIndent()
                else -> "ACK [5@0] {} unknown command\n"
            }
        }
        try {
            val status = DesktopMpdClient().readStatus(server.endpoint)
            server.await()

            assertEquals("play", status.state)
            assertEquals("A\nB", status.title)
            assertEquals(listOf("First", "Second"), status.artists)
            assertEquals("Test album", status.album)
            assertEquals(12.5, status.elapsedSeconds!!, 0.001)
            assertEquals(201.25, status.durationSeconds!!, 0.001)
            assertEquals(72, status.volumePercent)
            assertEquals(17L, status.playlistVersion)
            assertEquals(3, status.playlistLength)
            assertEquals(42, status.currentSongId)
            assertEquals(listOf("status", "currentsong"), server.commands)
        } finally {
            server.close()
        }
    }

    @Test
    fun `search escapes filter text and parses paged multi tag results`() = runBlocking {
        val server = MpdTestServer(expectedConnections = 2) { command ->
            when {
                command.startsWith("searchcount ") -> "songs: 22\nOK\n"
                command.startsWith("search ") -> """
                    file: music/one.flac
                    Title: One
                    Artist: First
                    Artist: Second
                    Album: Record
                    duration: 42.5
                    OK
                """.trimIndent()
                else -> "ACK [5@0] {} unknown command\n"
            }
        }
        try {
            val page = DesktopMpdClient().search(server.endpoint, "A 'B'", offset = 20)
            server.await()

            assertEquals(22, page.totalCount)
            assertEquals(20, page.offset)
            assertEquals(1, page.entries.size)
            assertEquals("music/one.flac", page.entries.single().uri)
            assertEquals(listOf("First", "Second"), page.entries.single().artists)
            assertEquals(42.5, page.entries.single().durationSeconds!!, 0.001)
            assertEquals(
                listOf(
                    "searchcount \"(any contains 'A \\\\'B\\\\'')\"",
                    "search \"(any contains 'A \\\\'B\\\\'')\" window 20:40",
                ),
                server.commands,
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `queue page uses stable ids and clamps an out of range offset`() = runBlocking {
        val server = MpdTestServer(expectedConnections = 3) { command ->
            when {
                command == "status" -> "playlist: 7\nplaylistlength: 2\nstate: stop\nOK\n"
                command == "playlistinfo 1:2" -> """
                    file: music/two.flac
                    Pos: 1
                    Id: 81
                    Title: Two
                    Artist: Artist
                    OK
                """.trimIndent()
                else -> "ACK [5@0] {} unknown command\n"
            }
        }
        try {
            val queue = DesktopMpdClient().readQueue(server.endpoint, requestedOffset = 99, pageSize = 1)
            server.await()

            assertEquals(7L, queue.playlistVersion)
            assertEquals(2, queue.totalCount)
            assertEquals(1, queue.offset)
            assertEquals(81, queue.entries.single().queueId)
            assertEquals(1, queue.entries.single().queuePosition)
            assertEquals(listOf("status", "playlistinfo 1:2", "status"), server.commands)
        } finally {
            server.close()
        }
    }

    @Test
    fun `retries a queue read when playlist version changes`() = runBlocking {
        val statusCount = AtomicInteger()
        val server = MpdTestServer(expectedConnections = 6) { command ->
            when {
                command == "status" -> {
                    val version = if (statusCount.getAndIncrement() == 0) 7 else 8
                    "playlist: $version\nplaylistlength: 1\nstate: stop\nOK\n"
                }
                command == "playlistinfo 0:1" -> "file: music/one.flac\nId: 3\nPos: 0\nOK\n"
                else -> "ACK [5@0] {} unknown command\n"
            }
        }
        try {
            val queue = DesktopMpdClient().readQueue(server.endpoint)
            server.await()

            assertEquals(8L, queue.playlistVersion)
            assertEquals(6, server.commands.size)
        } finally {
            server.close()
        }
    }

    @Test
    fun `rejects a queue read that keeps changing`() = runBlocking {
        val version = AtomicInteger()
        val server = MpdTestServer(expectedConnections = 6) { command ->
            when {
                command == "status" -> "playlist: ${version.incrementAndGet()}\nplaylistlength: 1\nOK\n"
                command == "playlistinfo 0:1" -> "file: music/one.flac\nId: 3\nPos: 0\nOK\n"
                else -> "ACK [5@0] {} unknown command\n"
            }
        }
        try {
            val error = assertThrows(IOException::class.java) {
                runBlocking { DesktopMpdClient().readQueue(server.endpoint) }
            }
            server.await()
            assertTrue(error.message!!.contains("queue changed"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `adds plays and removes using returned stable queue id`() = runBlocking {
        val server = MpdTestServer(expectedConnections = 3) { command ->
            when (command) {
                "addid \"music/quote\\\"slash\\\\file.flac\"" -> "Id: 101\nOK\n"
                "playid 101", "deleteid 101" -> "OK\n"
                else -> "ACK [5@0] {} unknown command\n"
            }
        }
        try {
            val client = DesktopMpdClient()
            val id = client.addToQueue(server.endpoint, "music/quote\"slash\\file.flac")
            client.playQueueItem(server.endpoint, id)
            client.deleteQueueItem(server.endpoint, id)
            server.await()

            assertEquals(101, id)
            assertEquals(
                listOf("addid \"music/quote\\\"slash\\\\file.flac\"", "playid 101", "deleteid 101"),
                server.commands,
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `authenticates every new connection before sending its command`() = runBlocking {
        val server = MpdTestServer(expectedConnections = 3) { command ->
            when (command) {
                "status" -> "state: stop\nvolume: 30\nOK\n"
                "currentsong" -> "OK\n"
                else -> "OK\n"
            }
        }
        try {
            val client = DesktopMpdClient()
            client.play(server.endpoint, "my pass")
            client.readStatus(server.endpoint, "my pass")
            server.await()

            assertEquals(
                listOf(
                    "password \"my pass\"", "play",
                    "password \"my pass\"", "status",
                    "password \"my pass\"", "currentsong",
                ),
                server.commands,
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `does not send a command after authentication is rejected`() {
        val server = MpdTestServer(expectedConnections = 1) { command ->
            if (command.startsWith("password ")) "ACK [3@0] {password} incorrect password\n"
            else "OK\n"
        }
        try {
            val error = assertThrows(DesktopMpdProtocolException::class.java) {
                runBlocking { DesktopMpdClient().play(server.endpoint, "bad") }
            }
            assertTrue(error.message!!.contains("incorrect password"))
            server.await()
            assertEquals(listOf("password \"bad\""), server.commands)
        } finally {
            server.close()
        }
    }

    @Test
    fun `sends transport seek and volume commands with invariant decimal`() = runBlocking {
        val server = MpdTestServer(expectedConnections = 5) { "OK\n" }
        try {
            val client = DesktopMpdClient()
            client.play(server.endpoint)
            client.pause(server.endpoint)
            client.stop(server.endpoint)
            client.seek(server.endpoint, 10.25)
            client.setVolume(server.endpoint, 80)
            server.await()

            assertEquals(listOf("play", "pause 1", "stop", "seekcur 10.250", "setvol 80"), server.commands)
        } finally {
            server.close()
        }
    }

    @Test
    fun `reports MPD ACK failures`() {
        val server = MpdTestServer(expectedConnections = 1) { "ACK [50@0] {play} No playlist\n" }
        try {
            val error = assertThrows(DesktopMpdProtocolException::class.java) {
                runBlocking { DesktopMpdClient().play(server.endpoint) }
            }
            assertTrue(error.message!!.contains("No playlist"))
            server.await()
        } finally {
            server.close()
        }
    }

    @Test
    fun `rejects an oversized protocol line`() {
        val server = MpdTestServer(expectedConnections = 1) {
            "metadata: ${"x".repeat(16_500)}\nOK\n"
        }
        try {
            val error = assertThrows(DesktopMpdProtocolException::class.java) {
                runBlocking { DesktopMpdClient().readStatus(server.endpoint) }
            }
            assertTrue(error.message!!.contains("supported size"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `rejects invalid endpoints and command values before connecting`() {
        assertThrows(IllegalArgumentException::class.java) {
            DesktopMpdEndpoint(" ", 6600).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            DesktopMpdEndpoint("localhost", 0).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { DesktopMpdClient().seek(DesktopMpdEndpoint("localhost", 6600), Double.NaN) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { DesktopMpdClient().setVolume(DesktopMpdEndpoint("localhost", 6600), 101) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { DesktopMpdClient().search(DesktopMpdEndpoint("localhost", 6600), " ") }
        }
    }
}

private class MpdTestServer(
    expectedConnections: Int,
    private val respond: (String) -> String,
) : AutoCloseable {
    private val server = ServerSocket(0, expectedConnections, InetAddress.getByName("127.0.0.1"))
    private val receivedCommands = Collections.synchronizedList(mutableListOf<String>())
    private val failure = AtomicReference<Throwable?>(null)
    val endpoint = DesktopMpdEndpoint("127.0.0.1", server.localPort)
    val commands: List<String> get() = synchronized(receivedCommands) { receivedCommands.toList() }
    private val worker = Thread {
        try {
            repeat(expectedConnections) {
                server.accept().use { socket -> handle(socket) }
            }
        } catch (error: Throwable) {
            if (!server.isClosed) failure.set(error)
        }
    }.apply {
        isDaemon = true
        start()
    }

    private fun handle(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
        writer.write("OK MPD 0.23.15\n")
        writer.flush()
        var command = reader.readLine() ?: error("client closed before sending an MPD command")
        receivedCommands += command
        var response = respond(command)
        writeResponse(writer, response)
        if (command.startsWith("password ") && response.trim() == "OK") {
            command = reader.readLine() ?: error("client closed before sending the authenticated command")
            receivedCommands += command
            response = respond(command)
            writeResponse(writer, response)
        }
    }

    private fun writeResponse(writer: BufferedWriter, response: String) {
        writer.write(response)
        if (!response.endsWith('\n')) writer.newLine()
        writer.flush()
    }

    fun await() {
        worker.join(5_000)
        assertTrue("fake MPD server did not finish", !worker.isAlive)
        failure.get()?.let { throw AssertionError("fake MPD server failed", it) }
    }

    override fun close() {
        server.close()
    }
}
