package dev.naominet.lazer

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopAudioCacheCancellationTest {
    @Test(timeout = 15_000)
    fun `clear cancels stalled headers and permits another download`() = stalledDownload(false, true)

    @Test(timeout = 15_000)
    fun `clear cancels stalled body and permits another download`() = stalledDownload(true, true)

    @Test(timeout = 15_000)
    fun `close returns promptly and terminates readers stalled at headers`() = stalledDownload(false, false)

    @Test(timeout = 15_000)
    fun `close returns promptly and terminates readers stalled in body`() = stalledDownload(true, false)

    private fun stalledDownload(sendHeaders: Boolean, clear: Boolean) {
        val directory = Files.createTempDirectory("lazer-cache-cancel")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val serverExecutor = Executors.newCachedThreadPool { task ->
            Thread(task, "cache-test-server").apply { isDaemon = true }
        }
        val workers = Executors.newCachedThreadPool { task ->
            Thread(task, "cache-test-reader").apply { isDaemon = true }
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = serverExecutor
        server.createContext("/stall") { exchange ->
            try {
                if (sendHeaders) {
                    exchange.sendResponseHeaders(200, 1024)
                    exchange.responseBody.write(42)
                    exchange.responseBody.flush()
                }
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            } finally {
                exchange.close()
            }
        }
        server.start()
        val cache = DesktopAudioCache(directory.resolve("cache")) { _, _ -> }
        try {
            val input = cache.open(7, "test", "http://127.0.0.1:${server.address.port}/stall", 1024)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            if (sendHeaders) {
                org.junit.Assert.assertEquals(42, workers.submit<Int> { input.read() }.get(3, TimeUnit.SECONDS))
            }
            val reading = CountDownLatch(1)
            val reader = workers.submit<Boolean> {
                reading.countDown()
                try {
                    input.read()
                    false
                } catch (_: IOException) {
                    true
                }
            }
            assertTrue(reading.await(3, TimeUnit.SECONDS))
            // The server stays blocked until cleanup: success cannot depend on network EOF.
            workers.submit {
                if (clear) runBlocking { cache.clear() } else cache.close()
            }.get(3, TimeUnit.SECONDS)
            assertTrue(reader.get(3, TimeUnit.SECONDS))
            assertTrue(!Files.exists(directory.resolve("cache/7-test.audio.complete")))
            input.close()

            if (clear) {
                val payload = ByteArray(96 * 1024) { it.toByte() }
                val source = directory.resolve("source.audio")
                Files.write(source, payload)
                val next = workers.submit<ByteArray> {
                    cache.open(8, "test", source.toUri().toString(), payload.size.toLong()).use { it.readBytes() }
                }.get(3, TimeUnit.SECONDS)
                assertArrayEquals(payload, next)
                runBlocking { cache.clear() }
            }
        } finally {
            cache.close()
            release.countDown()
            server.stop(0)
            serverExecutor.shutdownNow()
            workers.shutdownNow()
            // Joining cache cleanup before deleting also checks that cancelled writers release files.
            workers.awaitTermination(3, TimeUnit.SECONDS)
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
            }
        }
    }
}
