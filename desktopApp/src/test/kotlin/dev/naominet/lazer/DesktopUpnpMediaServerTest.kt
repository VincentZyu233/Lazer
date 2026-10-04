package dev.naominet.lazer

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

class DesktopUpnpMediaServerTest {
    @Test
    fun `serves authorized file with exact metadata for GET and HEAD`() = withFixture { wav ->
        DesktopUpnpMediaServer(LOOPBACK, LOOPBACK).use { server ->
            val lease = server.createLease(localSource(wav), "audio/x-wav")
            val token = lease.mediaUri.rawPath.substringAfterLast('/')
            assertEquals(43, token.length) // 32 random bytes in unpadded base64url.
            assertTrue(lease.mediaUri.rawPath.matches(Regex("^/media/[A-Za-z0-9_-]{43}$")))
            assertEquals(Files.size(wav), lease.fileSize)
            assertEquals("audio/x-wav", lease.mimeType)
            assertFalse(lease.mediaUri.toString().contains(wav.toAbsolutePath().toString()))

            val get = request(lease.mediaUri)
            assertEquals(200, get.status)
            assertArrayEquals(Files.readAllBytes(wav), get.body)
            assertEquals(Files.size(wav), get.contentLength)
            assertEquals("audio/x-wav", get.contentType)
            assertEquals("bytes", get.acceptRanges)
            assertEquals(null, get.contentRange)
            assertEquals(null, get.contentEncoding)

            val head = request(lease.mediaUri, "HEAD")
            assertEquals(200, head.status)
            assertTrue(head.body.isEmpty())
            assertEquals(Files.size(wav), head.contentLength)
            assertEquals("audio/x-wav", head.contentType)
            assertEquals("bytes", head.acceptRanges)
        }
    }

    @Test
    fun `serves closed open ended and suffix ranges with exact partial headers`() = withFixture { wav ->
        DesktopUpnpMediaServer(LOOPBACK, LOOPBACK).use { server ->
            val lease = server.createLease(localSource(wav))
            val data = Files.readAllBytes(wav)

            val closed = request(lease.mediaUri, range = "bytes=2-6")
            assertEquals(206, closed.status)
            assertArrayEquals(data.copyOfRange(2, 7), closed.body)
            assertEquals(5L, closed.contentLength)
            assertEquals("bytes 2-6/${data.size}", closed.contentRange)

            val open = request(lease.mediaUri, range = "bytes=9-")
            assertEquals(206, open.status)
            assertArrayEquals(data.copyOfRange(9, data.size), open.body)
            assertEquals("bytes 9-${data.lastIndex}/${data.size}", open.contentRange)

            val suffix = request(lease.mediaUri, range = "bytes=-4")
            assertEquals(206, suffix.status)
            assertArrayEquals(data.copyOfRange(data.size - 4, data.size), suffix.body)
            assertEquals("bytes ${data.size - 4}-${data.lastIndex}/${data.size}", suffix.contentRange)

            val capped = request(lease.mediaUri, range = "bytes=1-999999")
            assertEquals(206, capped.status)
            assertArrayEquals(data.copyOfRange(1, data.size), capped.body)

            val head = request(lease.mediaUri, method = "HEAD", range = "bytes=2-6")
            assertEquals(206, head.status)
            assertTrue(head.body.isEmpty())
            assertEquals(5L, head.contentLength)
            assertEquals("bytes 2-6/${data.size}", head.contentRange)
        }
    }

    @Test
    fun `rejects multiple malformed and unsatisfiable ranges with 416`() = withFixture { wav ->
        DesktopUpnpMediaServer(LOOPBACK, LOOPBACK).use { server ->
            val lease = server.createLease(localSource(wav))
            for (range in listOf("bytes=0-1,3-4", "items=0-1", "bytes=999999-", "bytes=8-2", "bytes=-0", "bytes=abc")) {
                val response = request(lease.mediaUri, range = range)
                assertEquals("$range should be rejected", 416, response.status)
                assertEquals(0L, response.contentLength)
                assertEquals("bytes */${Files.size(wav)}", response.contentRange)
                assertTrue(response.body.isEmpty())
            }
            val head = request(lease.mediaUri, method = "HEAD", range = "bytes=10000-")
            assertEquals(416, head.status)
            assertEquals("bytes */${Files.size(wav)}", head.contentRange)
            assertTrue(head.body.isEmpty())

            assertEquals(416, request(lease.mediaUri, rangeHeaders = listOf("bytes=0-1", "bytes=4-5")).status)
            assertEquals(416, request(lease.mediaUri, range = "bytes=+1-2").status)
        }
    }

    @Test
    fun `unknown revoked and expired bearer tokens do not expose media`() = withFixture { wav ->
        val clock = MutableClock(Instant.parse("2026-10-03T00:00:00Z"))
        DesktopUpnpMediaServer(LOOPBACK, LOOPBACK, clock = clock, leaseTtl = Duration.ofSeconds(5)).use { server ->
            val revoked = server.createLease(localSource(wav))
            revoked.revoke()
            assertEquals(404, request(revoked.mediaUri).status)
            revoked.close() // Idempotent.

            val live = server.createLease(localSource(wav))
            val unknown = URI(live.mediaUri.toString().dropLast(1) + if (live.mediaUri.toString().last() == 'A') "B" else "A")
            assertEquals(404, request(unknown).status)
            clock.advance(Duration.ofSeconds(6))
            assertEquals(404, request(live.mediaUri).status)
        }
    }

    @Test
    fun `renews a live lease without changing its URL and expires at the renewed deadline`() = withFixture { wav ->
        val clock = MutableClock(Instant.parse("2026-10-03T00:00:00Z"))
        DesktopUpnpMediaServer(LOOPBACK, LOOPBACK, clock = clock, leaseTtl = Duration.ofSeconds(5)).use { server ->
            val lease = server.createLease(localSource(wav))
            val mediaUri = lease.mediaUri

            clock.advance(Duration.ofSeconds(4))
            assertTrue(lease.renew())
            assertEquals(mediaUri, lease.mediaUri)

            // The original deadline has passed, but the same capability remains valid.
            clock.advance(Duration.ofSeconds(2))
            assertEquals(200, request(mediaUri).status)

            // The renewed lease expires at t=9, exactly five seconds after its renewal at t=4.
            clock.advance(Duration.ofSeconds(3))
            assertEquals(404, request(mediaUri).status)
        }
    }

    @Test
    fun `refuses to renew an expired lease and does not resurrect its token`() = withFixture { wav ->
        val clock = MutableClock(Instant.parse("2026-10-03T00:00:00Z"))
        DesktopUpnpMediaServer(LOOPBACK, LOOPBACK, clock = clock, leaseTtl = Duration.ofSeconds(5)).use { server ->
            val lease = server.createLease(localSource(wav))
            clock.advance(Duration.ofSeconds(5))

            assertFalse(lease.renew())
            assertEquals(404, request(lease.mediaUri).status)
            assertFalse(lease.renew())
        }
    }

    @Test
    fun `refuses to renew and revokes a lease after its source file changes`() = withFixture { wav ->
        val clock = MutableClock(Instant.parse("2026-10-03T00:00:00Z"))
        DesktopUpnpMediaServer(LOOPBACK, LOOPBACK, clock = clock, leaseTtl = Duration.ofSeconds(5)).use { server ->
            val lease = server.createLease(localSource(wav))
            val originalAttributes = Files.readAttributes(wav, java.nio.file.attribute.BasicFileAttributes::class.java)
            val replacement = wav.resolveSibling("replacement.wav")
            Files.write(replacement, wavBytes().also { it[it.lastIndex] = 0x55 })
            Files.setLastModifiedTime(replacement, FileTime.from(originalAttributes.lastModifiedTime().toInstant()))
            Files.move(replacement, wav, StandardCopyOption.REPLACE_EXISTING)

            assertFalse(lease.renew())
            assertEquals(404, request(lease.mediaUri).status)
        }
    }

    @Test
    fun `revoked and closed-server leases cannot be renewed`() = withFixture { wav ->
        val revokedServer = DesktopUpnpMediaServer(LOOPBACK, LOOPBACK)
        try {
            val lease = revokedServer.createLease(localSource(wav))
            lease.revoke()
            assertFalse(lease.renew())
        } finally {
            revokedServer.close()
        }

        val closedServer = DesktopUpnpMediaServer(LOOPBACK, LOOPBACK)
        val lease = closedServer.createLease(localSource(wav))
        closedServer.close()
        assertFalse(lease.renew())
    }

    @Test
    fun `reissues a live lease with a new token for the same snapshot and target TTL`() = withFixture { wav ->
        val clock = MutableClock(Instant.parse("2026-10-03T00:00:00Z"))
        val replacementAddress = InetAddress.getByName("127.0.0.2")
        DesktopUpnpMediaServer(
            LOOPBACK,
            LOOPBACK,
            clock = clock,
            leaseTtl = Duration.ofSeconds(5),
        ).use { sourceServer ->
            val originalLease = sourceServer.createLease(localSource(wav), "audio/x-wav")
            val originalUri = originalLease.mediaUri
            clock.advance(Duration.ofSeconds(4))

            DesktopUpnpMediaServer(
                replacementAddress,
                LOOPBACK,
                clock = clock,
                leaseTtl = Duration.ofSeconds(10),
            ).use { targetServer ->
                val replacementLease = targetServer.reissueLease(originalLease, mimeType = "audio/wav")
                assertNotNull(replacementLease)
                val newLease = requireNotNull(replacementLease)
                assertFalse(originalUri == newLease.mediaUri)
                assertFalse(originalUri.rawPath.substringAfterLast('/') == newLease.mediaUri.rawPath.substringAfterLast('/'))
                assertEquals(originalLease.fileSize, newLease.fileSize)
                assertEquals("audio/wav", newLease.mimeType)
                assertEquals("audio/wav", request(newLease.mediaUri).contentType)
                assertArrayEquals(Files.readAllBytes(wav), request(newLease.mediaUri).body)

                // The source lease expires at t=5; the reissued URL remains valid until t=14.
                clock.advance(Duration.ofSeconds(2))
                assertEquals(404, request(originalUri).status)
                assertEquals(200, request(newLease.mediaUri).status)
                clock.advance(Duration.ofSeconds(8))
                assertEquals(404, request(newLease.mediaUri).status)
            }
        }
    }

    @Test
    fun `refuses to reissue revoked expired or closed source leases`() = withFixture { wav ->
        val clock = MutableClock(Instant.parse("2026-10-03T00:00:00Z"))
        DesktopUpnpMediaServer(LOOPBACK, LOOPBACK, clock = clock).use { targetServer ->
            DesktopUpnpMediaServer(LOOPBACK, LOOPBACK).use { revokedServer ->
                val revokedLease = revokedServer.createLease(localSource(wav))
                revokedLease.revoke()
                assertNull(targetServer.reissueLease(revokedLease))
            }

            DesktopUpnpMediaServer(
                LOOPBACK,
                LOOPBACK,
                clock = clock,
                leaseTtl = Duration.ofSeconds(5),
            ).use { expiringServer ->
                val expiredLease = expiringServer.createLease(localSource(wav))
                clock.advance(Duration.ofSeconds(5))
                assertNull(targetServer.reissueLease(expiredLease))
                assertEquals(404, request(expiredLease.mediaUri).status)
            }

            val closedSourceServer = DesktopUpnpMediaServer(LOOPBACK, LOOPBACK)
            val closedSourceLease = closedSourceServer.createLease(localSource(wav))
            closedSourceServer.close()
            assertNull(targetServer.reissueLease(closedSourceLease))
        }
    }

    @Test
    fun `refuses reissue when target server is closed and leaves source lease valid`() = withFixture { wav ->
        DesktopUpnpMediaServer(LOOPBACK, LOOPBACK).use { sourceServer ->
            val sourceLease = sourceServer.createLease(localSource(wav))
            val closedTargetServer = DesktopUpnpMediaServer(InetAddress.getByName("127.0.0.2"), LOOPBACK)
            closedTargetServer.close()

            assertNull(closedTargetServer.reissueLease(sourceLease))
            assertEquals(200, request(sourceLease.mediaUri).status)
        }
    }

    @Test
    fun `refuses to reissue a snapshot under an incompatible MIME type`() = withFixture { wav ->
        DesktopUpnpMediaServer(LOOPBACK, LOOPBACK).use { sourceServer ->
            val sourceLease = sourceServer.createLease(localSource(wav))
            DesktopUpnpMediaServer(InetAddress.getByName("127.0.0.2"), LOOPBACK).use { targetServer ->
                assertNull(targetServer.reissueLease(sourceLease, mimeType = "audio/flac"))
                assertEquals(200, request(sourceLease.mediaUri).status)
            }
        }
    }

    @Test
    fun `refuses to reissue after the source changes or is replaced`() = withFixture { wav ->
        val changed = wav.resolveSibling("changed.wav")
        val replaced = wav.resolveSibling("replaced.wav")
        Files.copy(wav, changed)
        Files.copy(wav, replaced)
        DesktopUpnpMediaServer(LOOPBACK, LOOPBACK).use { sourceServer ->
            DesktopUpnpMediaServer(InetAddress.getByName("127.0.0.2"), LOOPBACK).use { targetServer ->
                val changedLease = sourceServer.createLease(localSource(changed))
                val changedAttributes = Files.readAttributes(
                    changed,
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                )
                Files.write(changed, wavBytes().also { it[it.lastIndex] = 0x55 })
                Files.setLastModifiedTime(
                    changed,
                    FileTime.from(changedAttributes.lastModifiedTime().toInstant().plusSeconds(2)),
                )
                assertNull(targetServer.reissueLease(changedLease))
                assertEquals(404, request(changedLease.mediaUri).status)

                val replacedLease = sourceServer.createLease(localSource(replaced))
                val originalAttributes = Files.readAttributes(
                    replaced,
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                )
                val replacement = replaced.resolveSibling("replacement.wav")
                Files.write(replacement, wavBytes().also { it[it.lastIndex] = 0x66 })
                Files.setLastModifiedTime(replacement, FileTime.from(originalAttributes.lastModifiedTime().toInstant()))
                Files.move(replacement, replaced, StandardCopyOption.REPLACE_EXISTING)

                assertNull(targetServer.reissueLease(replacedLease))
                assertEquals(404, request(replacedLease.mediaUri).status)
            }
        }
    }

    @Test
    fun `refuses reissue after source path becomes a symbolic link`() = withFixture { wav ->
        val linkTarget = wav.resolveSibling("link-target.wav")
        Files.write(linkTarget, wavBytes())
        DesktopUpnpMediaServer(LOOPBACK, LOOPBACK).use { sourceServer ->
            DesktopUpnpMediaServer(InetAddress.getByName("127.0.0.2"), LOOPBACK).use { targetServer ->
                val sourceLease = sourceServer.createLease(localSource(wav))
                Files.delete(wav)
                try {
                    Files.createSymbolicLink(wav, linkTarget.fileName)
                } catch (error: UnsupportedOperationException) {
                    assumeNoException("The filesystem does not support symbolic links", error)
                } catch (error: SecurityException) {
                    assumeNoException("The test account cannot create symbolic links", error)
                } catch (error: java.io.IOException) {
                    assumeNoException("The filesystem does not support symbolic links", error)
                }

                assertNull(targetServer.reissueLease(sourceLease))
                assertEquals(404, request(sourceLease.mediaUri).status)
            }
        }
    }

    @Test
    fun `staged media server keeps replacement lease available while old route is retired`() = withFixture { wav ->
        val replacementAddress = InetAddress.getByName("127.0.0.2")
        DesktopUpnpMediaServer(LOOPBACK, LOOPBACK).use { oldServer ->
            val oldLease = oldServer.createLease(localSource(wav))
            assertEquals(200, request(oldLease.mediaUri).status)

            DesktopUpnpMediaServer(replacementAddress, LOOPBACK).use { replacementServer ->
                val replacementLease = replacementServer.createLease(localSource(wav))
                assertEquals(200, request(replacementLease.mediaUri).status)

                // The renderer can switch to the new URI before the previous server is released.
                oldLease.revoke()
                assertEquals(404, request(oldLease.mediaUri).status)
                oldServer.close()
                assertEquals(200, request(replacementLease.mediaUri).status)

                replacementLease.revoke()
                assertEquals(404, request(replacementLease.mediaUri).status)
            }
        }
    }

    @Test
    fun `limits requests to configured renderer source address`() = withFixture { wav ->
        val renderer = InetAddress.getByName("127.0.0.2")
        assertFalse(isDesktopUpnpRequestFromRenderer(LOOPBACK, renderer))
        DesktopUpnpMediaServer(LOOPBACK, renderer).use { server ->
            val lease = server.createLease(localSource(wav))
            assertEquals(403, request(lease.mediaUri).status)
        }
    }

    @Test
    fun `only whole non CUE local WAV or FLAC files and matching MIME values can be leased`() = withFixture { wav ->
        DesktopUpnpMediaServer(LOOPBACK, LOOPBACK).use { server ->
            assertIllegalArgument { server.createLease(localSource(wav, cueSheetPath = "album.cue")) }
            assertIllegalArgument { server.createLease(localSource(wav, cueTrackNumber = 2)) }
            assertIllegalArgument { server.createLease(localSource(wav, cueStartFrame75 = 1)) }
            assertIllegalArgument { server.createLease(localSource(wav, cueEndFrame75 = 10)) }
            assertIllegalArgument { server.createLease(localSource(wav), "audio/flac") }
            val mp3 = wav.resolveSibling("track.mp3")
            Files.write(mp3, byteArrayOf(1, 2, 3, 4))
            assertIllegalArgument { server.createLease(localSource(mp3), "audio/wav") }

            val flac = wav.resolveSibling("track.flac")
            Files.write(flac, "fLaC-test stream".toByteArray(Charsets.US_ASCII))
            val lease = server.createLease(localSource(flac))
            assertEquals("audio/flac", lease.mimeType)
            assertEquals(200, request(lease.mediaUri).status)
        }
    }

    @Test
    fun `rejects symbolic links`() = withFixture { wav ->
        DesktopUpnpMediaServer(LOOPBACK, LOOPBACK).use { server ->
            val link = wav.resolveSibling("linked.wav")
            try {
                Files.createSymbolicLink(link, wav.fileName)
                assertIllegalArgument { server.createLease(localSource(link)) }
            } catch (error: UnsupportedOperationException) {
                assumeNoException("The filesystem does not support symbolic links", error)
            } catch (error: SecurityException) {
                assumeNoException("The test account cannot create symbolic links", error)
            } catch (error: java.io.IOException) {
                assumeNoException("The filesystem does not support symbolic links", error)
            }
        }
    }

    @Test
    fun `refuses same size and mtime file replacement after lease creation`() = withFixture { wav ->
        DesktopUpnpMediaServer(LOOPBACK, LOOPBACK).use { server ->
            val lease = server.createLease(localSource(wav))
            val originalAttributes = Files.readAttributes(wav, java.nio.file.attribute.BasicFileAttributes::class.java)
            val replacement = wav.resolveSibling("replacement.wav")
            Files.write(replacement, wavBytes().also { it[it.lastIndex] = 0x55 })
            Files.setLastModifiedTime(replacement, FileTime.from(originalAttributes.lastModifiedTime().toInstant()))
            Files.move(replacement, wav, StandardCopyOption.REPLACE_EXISTING)
            assertEquals(410, request(lease.mediaUri).status)
        }
    }

    @Test
    fun `path is an opaque capability and arbitrary filesystem routes are not served`() = withFixture { wav ->
        DesktopUpnpMediaServer(LOOPBACK, LOOPBACK).use { server ->
            val lease = server.createLease(localSource(wav))
            assertEquals(404, request(URI(lease.mediaUri.toString() + "/extra")).status)
            assertEquals(404, request(URI(lease.mediaUri.toString() + "?path=private-file.wav")).status)
            assertEquals(404, request(URI("${lease.mediaUri.scheme}://${lease.mediaUri.rawAuthority}/media/../${wav.fileName}")).status)
            assertEquals(405, request(lease.mediaUri, method = "PUT").status)

            val selected = DesktopUpnpMediaServer.routeLocalAddress(LOOPBACK, 1900)
            assertTrue(selected.isLoopbackAddress)
            assertNotNull(server.localAddress.address)
            assertFalse(server.localAddress.address.isAnyLocalAddress)
        }
    }

    private fun localSource(
        path: Path,
        cueSheetPath: String? = null,
        cueTrackNumber: Int? = null,
        cueStartFrame75: Long = 0L,
        cueEndFrame75: Long = 0L,
    ) = DesktopTrackSource.LocalFile(
        absolutePath = path.toAbsolutePath().toString(),
        cueSheetPath = cueSheetPath,
        cueTrackNumber = cueTrackNumber,
        cueStartFrame75 = cueStartFrame75,
        cueEndFrame75 = cueEndFrame75,
    )

    private fun withFixture(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("lazer-upnp-media-")
        try {
            val wav = directory.resolve("track.wav")
            Files.write(wav, wavBytes())
            test(wav)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun wavBytes(): ByteArray = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(40)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1.toShort()) // PCM
        putShort(1.toShort()) // Mono
        putInt(44_100)
        putInt(88_200)
        putShort(2.toShort())
        putShort(16.toShort())
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(4)
        put(byteArrayOf(1, 2, 3, 4))
    }.array()

    private fun request(
        uri: URI,
        method: String = "GET",
        range: String? = null,
        rangeHeaders: List<String>? = null,
    ): HttpResponse {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        connection.requestMethod = method
        if (rangeHeaders != null) rangeHeaders.forEach { connection.addRequestProperty("Range", it) }
        else if (range != null) connection.setRequestProperty("Range", range)
        return try {
            val status = connection.responseCode
            val bodyStream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = bodyStream?.use { it.readBytes() } ?: ByteArray(0)
            HttpResponse(
                status = status,
                body = body,
                contentLength = connection.getHeaderFieldLong("Content-Length", -1L),
                contentType = connection.getHeaderField("Content-Type"),
                contentRange = connection.getHeaderField("Content-Range"),
                acceptRanges = connection.getHeaderField("Accept-Ranges"),
                contentEncoding = connection.getHeaderField("Content-Encoding"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    private data class HttpResponse(
        val status: Int,
        val body: ByteArray,
        val contentLength: Long,
        val contentType: String?,
        val contentRange: String?,
        val acceptRanges: String?,
        val contentEncoding: String?,
    )

    private class MutableClock(initial: Instant) : Clock() {
        private val current = AtomicReference(initial)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current.get()
        fun advance(duration: Duration) { current.updateAndGet { it.plus(duration) } }
    }

    companion object {
        private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
