package dev.naominet.lazer

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSeekableAudioSourceTest {
    @Test(timeout = 15_000)
    fun `close wakes a blocked read and serializes concurrent reader closes`() {
        val directory = Files.createTempDirectory("lazer-seekable-read-close")
        val enteredWait = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val startClose = CyclicBarrier(3)
        val serverExecutor = Executors.newCachedThreadPool()
        val workers = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = serverExecutor
        server.createContext("/stall") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                check(releaseServer.await(8, TimeUnit.SECONDS))
            } finally {
                exchange.close()
            }
        }
        server.start()
        val cache = DesktopAudioCache(
            cacheDirectory = directory.resolve("cache"),
            beforeReadAwaitForTest = { _, _ -> enteredWait.countDown() },
            onProgress = { _, _ -> },
        )
        var source: DesktopSeekableAudioSource? = null
        try {
            val reader = cache.openSeekable(
                trackId = 36,
                variantKey = "read-close",
                url = "http://127.0.0.1:${server.address.port}/stall",
                expectedBytes = null,
            )
            source = reader
            val pendingRead = workers.submit<Int> { reader.read(ByteArray(1), 1) }
            assertTrue("reader should report entering the download wait", enteredWait.await(3, TimeUnit.SECONDS))
            val firstClose = workers.submit { startClose.await(); reader.close() }
            val secondClose = workers.submit { startClose.await(); reader.close() }
            startClose.await(3, TimeUnit.SECONDS)
            firstClose.get(3, TimeUnit.SECONDS)
            secondClose.get(3, TimeUnit.SECONDS)
            assertEquals(-1, pendingRead.get(3, TimeUnit.SECONDS))
            assertEquals(-1, reader.read(ByteArray(1), 1))
        } finally {
            releaseServer.countDown()
            runCatching { source?.close() }
            server.stop(0)
            runCatching { runBlocking { cache.clear() } }
            cache.close()
            serverExecutor.shutdownNow()
            workers.shutdownNow()
            deleteTree(directory)
        }
    }

    @Test(timeout = 15_000)
    fun `seekable reader waits for appended bytes and returns EOF after completed response`() {
        val directory = Files.createTempDirectory("lazer-seekable-growth")
        val first = byteArrayOf(11, 12, 13)
        val second = byteArrayOf(21, 22, 23)
        val firstSent = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val allowResponseEnd = CountDownLatch(1)
        val serverExecutor = Executors.newCachedThreadPool()
        val workers = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = serverExecutor
        server.createContext("/growing") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(first)
                exchange.responseBody.flush()
                firstSent.countDown()
                check(releaseSecond.await(5, TimeUnit.SECONDS))
                exchange.responseBody.write(second)
                exchange.responseBody.flush()
                check(allowResponseEnd.await(5, TimeUnit.SECONDS))
            } finally {
                exchange.close()
            }
        }
        server.start()
        val cache = DesktopAudioCache(directory.resolve("cache")) { _, _ -> }
        var source: DesktopSeekableAudioSource? = null
        try {
            val reader = cache.openSeekable(
                trackId = 31,
                variantKey = "growing",
                url = "http://127.0.0.1:${server.address.port}/growing",
                expectedBytes = null,
            )
            source = reader
            assertTrue(firstSent.await(3, TimeUnit.SECONDS))

            val actualFirst = ByteArray(first.size)
            assertEquals(first.size, reader.read(actualFirst, actualFirst.size))
            assertArrayEquals(first, actualFirst)

            val actualSecond = ByteArray(second.size)
            val pendingRead = workers.submit<Int> { reader.read(actualSecond, actualSecond.size) }
            Thread.sleep(100)
            assertFalse("read should wait while the growing cache has no new bytes", pendingRead.isDone)
            releaseSecond.countDown()
            assertEquals(second.size, pendingRead.get(3, TimeUnit.SECONDS))
            assertArrayEquals(second, actualSecond)

            val pendingEof = workers.submit<Int> { reader.read(ByteArray(1), 1) }
            Thread.sleep(100)
            assertFalse("EOF must wait until the HTTP response really ends", pendingEof.isDone)
            allowResponseEnd.countDown()
            assertEquals(-1, pendingEof.get(3, TimeUnit.SECONDS))
        } finally {
            releaseSecond.countDown()
            allowResponseEnd.countDown()
            runCatching { source?.close() }
            server.stop(0)
            runCatching { runBlocking { cache.clear() } }
            cache.close()
            serverExecutor.shutdownNow()
            workers.shutdownNow()
            deleteTree(directory)
        }
    }

    @Test(timeout = 15_000)
    fun `download failure and cache cancellation throw instead of reporting EOF`() {
        val directory = Files.createTempDirectory("lazer-seekable-failure")
        val payload = byteArrayOf(31, 32, 33, 34)
        val serverExecutor = Executors.newCachedThreadPool()
        val workers = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = serverExecutor
        server.createContext("/short") { exchange ->
            try {
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.write(payload)
            } finally {
                exchange.close()
            }
        }
        val enteredStall = CountDownLatch(1)
        val releaseStall = CountDownLatch(1)
        server.createContext("/stall") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(41)
                exchange.responseBody.flush()
                enteredStall.countDown()
                check(releaseStall.await(8, TimeUnit.SECONDS))
            } finally {
                exchange.close()
            }
        }
        server.start()
        val failureCache = DesktopAudioCache(directory.resolve("failure-cache")) { _, _ -> }
        val cancelCache = DesktopAudioCache(directory.resolve("cancel-cache")) { _, _ -> }
        var failedSource: DesktopSeekableAudioSource? = null
        var cancelledSource: DesktopSeekableAudioSource? = null
        try {
            val failedReader = failureCache.openSeekable(
                trackId = 32,
                variantKey = "short",
                url = "http://127.0.0.1:${server.address.port}/short",
                // The transport ends cleanly at four bytes, but the requested resource was longer.
                expectedBytes = 8,
            )
            failedSource = failedReader
            val actual = ByteArray(payload.size)
            assertEquals(payload.size, failedReader.read(actual, actual.size))
            assertArrayEquals(payload, actual)
            val failedRead = workers.submit<Int> { failedReader.read(ByteArray(1), 1) }
            assertTrue(awaitIoFailure(failedRead))

            val cancelledReader = cancelCache.openSeekable(
                trackId = 33,
                variantKey = "cancel",
                url = "http://127.0.0.1:${server.address.port}/stall",
                expectedBytes = 16,
            )
            cancelledSource = cancelledReader
            assertTrue(enteredStall.await(3, TimeUnit.SECONDS))
            assertEquals(1, cancelledReader.read(ByteArray(1), 1))
            val cancelledRead = workers.submit<Int> { cancelledReader.read(ByteArray(1), 1) }
            Thread.sleep(100)
            assertFalse("reader should wait for the next byte before cancellation", cancelledRead.isDone)
            runBlocking { cancelCache.clear() }
            assertTrue(awaitIoFailure(cancelledRead))
        } finally {
            releaseStall.countDown()
            runCatching { failedSource?.close() }
            runCatching { cancelledSource?.close() }
            server.stop(0)
            runCatching { runBlocking { failureCache.clear() } }
            runCatching { runBlocking { cancelCache.clear() } }
            failureCache.close()
            cancelCache.close()
            serverExecutor.shutdownNow()
            workers.shutdownNow()
            deleteTree(directory)
        }
    }

    @Test(timeout = 15_000)
    fun `local source close wakes a blocked read as EOF`() {
        val directory = Files.createTempDirectory("lazer-seekable-close")
        val enteredStall = CountDownLatch(1)
        val releaseStall = CountDownLatch(1)
        val serverExecutor = Executors.newCachedThreadPool()
        val workers = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = serverExecutor
        server.createContext("/stall") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                enteredStall.countDown()
                check(releaseStall.await(8, TimeUnit.SECONDS))
            } finally {
                exchange.close()
            }
        }
        server.start()
        val cache = DesktopAudioCache(directory.resolve("cache")) { _, _ -> }
        var source: DesktopSeekableAudioSource? = null
        try {
            val reader = cache.openSeekable(
                trackId = 34,
                variantKey = "close",
                url = "http://127.0.0.1:${server.address.port}/stall",
                expectedBytes = 16,
            )
            source = reader
            assertTrue(enteredStall.await(3, TimeUnit.SECONDS))
            val blockedRead = workers.submit<Int> { reader.read(ByteArray(1), 1) }
            Thread.sleep(100)
            assertFalse(blockedRead.isDone)
            reader.close()
            assertEquals(-1, blockedRead.get(3, TimeUnit.SECONDS))
        } finally {
            releaseStall.countDown()
            runCatching { source?.close() }
            server.stop(0)
            runCatching { runBlocking { cache.clear() } }
            cache.close()
            serverExecutor.shutdownNow()
            workers.shutdownNow()
            deleteTree(directory)
        }
    }

    @Test(timeout = 15_000)
    fun `seek observes an update that lands between availability check and wait`() {
        val directory = Files.createTempDirectory("lazer-seekable-seek-race")
        val first = byteArrayOf(51, 52, 53, 54)
        val second = byteArrayOf(61, 62, 63, 64)
        val firstSent = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val secondSent = CountDownLatch(1)
        val allowResponseEnd = CountDownLatch(1)
        val hookEntered = CountDownLatch(1)
        val serverExecutor = Executors.newCachedThreadPool()
        val workers = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = serverExecutor
        server.createContext("/race") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(first)
                exchange.responseBody.flush()
                firstSent.countDown()
                check(releaseSecond.await(5, TimeUnit.SECONDS))
                exchange.responseBody.write(second)
                exchange.responseBody.flush()
                secondSent.countDown()
                // Keep the transfer active so no later EOF wake can hide a lost data wake.
                check(allowResponseEnd.await(5, TimeUnit.SECONDS))
            } finally {
                exchange.close()
            }
        }
        server.start()
        val cache = DesktopAudioCache(
            cacheDirectory = directory.resolve("cache"),
            beforeSeekAwaitForTest = { observedRevision, currentRevision ->
                hookEntered.countDown()
                releaseSecond.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (currentRevision() == observedRevision && System.nanoTime() < deadline) {
                    Thread.sleep(1)
                }
                assertTrue(
                    "the test download must publish its data revision at the seek/wait boundary",
                    currentRevision() != observedRevision,
                )
            },
            onProgress = { _, _ -> },
        )
        var source: DesktopSeekableAudioSource? = null
        try {
            val reader = cache.openSeekable(
                trackId = 35,
                variantKey = "seek-race",
                url = "http://127.0.0.1:${server.address.port}/race",
                expectedBytes = null,
            )
            source = reader
            assertTrue(firstSent.await(3, TimeUnit.SECONDS))
            assertEquals(first.size, reader.read(ByteArray(first.size), first.size))

            val pendingSeek = workers.submit<Long> { reader.seek((first.size + second.size).toLong()) }
            assertTrue(hookEntered.await(3, TimeUnit.SECONDS))
            assertTrue(secondSent.await(3, TimeUnit.SECONDS))
            assertEquals((first.size + second.size).toLong(), pendingSeek.get(3, TimeUnit.SECONDS))
        } finally {
            releaseSecond.countDown()
            allowResponseEnd.countDown()
            runCatching { source?.close() }
            server.stop(0)
            runCatching { runBlocking { cache.clear() } }
            cache.close()
            serverExecutor.shutdownNow()
            workers.shutdownNow()
            deleteTree(directory)
        }
    }

    private fun awaitIoFailure(future: java.util.concurrent.Future<Int>): Boolean = try {
        future.get(3, TimeUnit.SECONDS)
        false
    } catch (error: ExecutionException) {
        error.cause is IOException
    }

    private fun deleteTree(path: java.nio.file.Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
