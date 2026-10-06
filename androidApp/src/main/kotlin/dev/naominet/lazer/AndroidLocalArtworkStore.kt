package dev.naominet.lazer

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Persists bounded embedded covers so queue snapshots can keep showing them after a restart. */
internal object AndroidLocalArtworkStore {
    const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 256L * 1024L * 1024L
    private const val MAX_SIDE = 16_384
    private const val MAX_PIXELS = 64_000_000L
    private val lock = Any()
    private val hex = "0123456789abcdef".toCharArray()

    fun persist(context: Context, bytes: ByteArray): String? {
        if (bytes.size !in 16..MAX_IMAGE_BYTES) return null
        val extension = imageExtension(bytes) ?: return null
        val dimensions = imageDimensions(bytes, extension) ?: return null
        val (width, height) = dimensions
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE || width.toLong() * height > MAX_PIXELS) {
            return null
        }

        val name = sha256(bytes) + ".$extension"
        val directory = File(context.filesDir, "local-artwork")
        synchronized(lock) {
            if (!directory.isDirectory && !directory.mkdirs()) return null
            val target = File(directory, name)
            if (target.isFile && target.length() == bytes.size.toLong()) {
                target.setLastModified(System.currentTimeMillis())
                return Uri.fromFile(target).toString()
            }

            val temporary = runCatching { File.createTempFile("cover-", ".tmp", directory) }.getOrNull()
                ?: return null
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                if (target.exists() && !target.delete()) return null
                if (!temporary.renameTo(target)) return null
                target.setLastModified(System.currentTimeMillis())
                prune(directory, keep = target)
                return Uri.fromFile(target).toString()
            } catch (_: Exception) {
                return null
            } finally {
                temporary.delete()
            }
        }
    }

    private fun prune(directory: File, keep: File) {
        val files = directory.listFiles().orEmpty().filter(File::isFile)
        var total = files.sumOf(File::length)
        if (total <= MAX_CACHE_BYTES) return
        for (file in files.asSequence().filter { it != keep }.sortedBy(File::lastModified)) {
            if (total <= MAX_CACHE_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun imageExtension(bytes: ByteArray): String? = when {
        bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> "jpg"
        bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "png"
        bytes.startsWith("GIF87a".encodeToByteArray()) || bytes.startsWith("GIF89a".encodeToByteArray()) -> "gif"
        bytes.size >= 12 && bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WEBP" -> "webp"
        else -> null
    }

    private fun imageDimensions(bytes: ByteArray, extension: String): Pair<Int, Int>? = when (extension) {
        "png" -> if (bytes.size >= 24 && bytes.uint32Be(8) == 13 && bytes.ascii(12, 4) == "IHDR") {
            bytes.uint32Be(16) to bytes.uint32Be(20)
        } else {
            null
        }
        "gif" -> if (bytes.size >= 10) bytes.uint16Le(6) to bytes.uint16Le(8) else null
        else -> {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            options.outWidth.takeIf { it > 0 }?.let { width ->
                options.outHeight.takeIf { it > 0 }?.let { height -> width to height }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(hex[value ushr 4])
                append(hex[value and 0x0F])
            }
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.ascii(offset: Int, count: Int): String =
        String(this, offset, count, Charsets.US_ASCII)

    private fun ByteArray.uint32Be(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)

    private fun ByteArray.uint16Le(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
}

internal fun persistAndroidEmbeddedArtwork(context: Context, bytes: ByteArray): String? =
    AndroidLocalArtworkStore.persist(context, bytes)
