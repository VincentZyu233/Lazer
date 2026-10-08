package dev.naominet.lazer

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal enum class AndroidDsdOutputMode {
    Pcm,
    DoP,
}

/** Routes local DSD assets through the native PCM or DoP reader, wrapped as a virtual WAV. */
@UnstableApi
internal class AndroidDsdPcmDataSourceFactory(
    context: Context,
    private val upstreamFactory: DataSource.Factory,
    private val decoderFactory: AndroidDsdPcmDecoderFactory = NativeAndroidDsdPcmDecoderFactory,
    private val outputMode: AndroidDsdOutputMode = AndroidDsdOutputMode.Pcm,
) : DataSource.Factory {
    private val appContext = context.applicationContext

    override fun createDataSource(): DataSource =
        AndroidDsdPcmDataSource(appContext, upstreamFactory, decoderFactory, outputMode)
}

@UnstableApi
private class AndroidDsdPcmDataSource(
    private val context: Context,
    private val upstreamFactory: DataSource.Factory,
    private val decoderFactory: AndroidDsdPcmDecoderFactory,
    private val outputMode: AndroidDsdOutputMode,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var upstream: DataSource? = null
    private var sourceUri: Uri? = null
    private var sourceDescriptor: ParcelFileDescriptor? = null
    private var sourceAssetDescriptor: AssetFileDescriptor? = null
    private var copiedSource: File? = null
    private var floatPcmDataSource: AndroidFloatPcmWavDataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        upstream?.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        close()
        val uri = dataSpec.uri
        if (!isLocalDsdUri(context, uri)) {
            val source = upstreamFactory.createDataSource()
            listeners.forEach(source::addTransferListener)
            upstream = source
            return source.open(dataSpec)
        }

        sourceUri = uri
        try {
            val openedFile = openSeekableDsdFile(context, uri)
            sourceDescriptor = openedFile.descriptor
            sourceAssetDescriptor = openedFile.assetDescriptor
            copiedSource = openedFile.temporaryCopy
            val decoder = decoderFactory.open(
                openedFile.descriptor.fd,
                openedFile.startOffset,
                openedFile.length,
                outputMode,
            )
            val virtualSource = AndroidFloatPcmWavDataSource(decoder, uri)
            floatPcmDataSource = virtualSource
            return virtualSource.open(dataSpec)
        } catch (failure: Throwable) {
            close()
            if (failure is IOException) throw failure
            if (failure is LinkageError) {
                throw IOException("Native DSD-to-PCM support is unavailable for this device ABI.", failure)
            }
            throw IOException("Could not prepare the selected DSD file.", failure)
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        require(offset >= 0 && readLength >= 0 && offset + readLength <= buffer.size)
        if (readLength == 0) return 0
        upstream?.let { return it.read(buffer, offset, readLength) }
        return floatPcmDataSource?.read(buffer, offset, readLength) ?: C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = upstream?.uri ?: floatPcmDataSource?.uri ?: sourceUri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders ?: emptyMap()

    @Throws(IOException::class)
    override fun close() {
        var failure: IOException? = null
        val activeUpstream = upstream
        upstream = null
        try {
            activeUpstream?.close()
        } catch (error: IOException) {
            failure = error
        }
        val activeFloatPcmSource = floatPcmDataSource
        floatPcmDataSource = null
        try {
            activeFloatPcmSource?.close()
        } catch (error: IOException) {
            if (failure == null) failure = error
        }
        val activeAssetDescriptor = sourceAssetDescriptor
        runCatching {
            if (activeAssetDescriptor != null) activeAssetDescriptor.close()
            else sourceDescriptor?.close()
        }.onFailure { error ->
            if (failure == null && error is IOException) failure = error
        }
        sourceAssetDescriptor = null
        sourceDescriptor = null
        copiedSource?.delete()
        copiedSource = null
        sourceUri = null
        failure?.let { throw it }
    }
}

/** Opens the JNI decoder in production and allows deterministic decoder seams in JVM tests. */
internal fun interface AndroidDsdPcmDecoderFactory {
    @Throws(IOException::class)
    fun open(
        fileDescriptor: Int,
        startOffset: Long,
        length: Long,
        outputMode: AndroidDsdOutputMode,
    ): AndroidFloatPcmDecoder
}

private object NativeAndroidDsdPcmDecoderFactory : AndroidDsdPcmDecoderFactory {
    override fun open(
        fileDescriptor: Int,
        startOffset: Long,
        length: Long,
        outputMode: AndroidDsdOutputMode,
    ): AndroidFloatPcmDecoder {
        val outputInfo = LongArray(5)
        val handle = AndroidDsdPcmNative.nativeOpen(
            fileDescriptor,
            startOffset,
            length,
            0, // Retain DSD64 at 176.4 kHz and cap higher rates at 192 kHz.
            outputMode == AndroidDsdOutputMode.DoP,
            outputInfo,
        )
        if (handle == 0L) {
            throw IOException(AndroidDsdPcmNative.nativeLastError(0L).ifBlank {
                "The selected file could not be decoded as DSD."
            })
        }

        val sampleRate = outputInfo[0].toInt()
        val channelCount = outputInfo[1].toInt()
        val totalFrames = outputInfo[4]
        val outputEncoding = when (outputMode) {
            AndroidDsdOutputMode.Pcm -> C.ENCODING_PCM_FLOAT
            AndroidDsdOutputMode.DoP -> C.ENCODING_PCM_24BIT
        }
        if (sampleRate <= 0 || channelCount !in 1..8 || totalFrames <= 0L ||
            (outputMode == AndroidDsdOutputMode.DoP &&
                (channelCount != 2 || sampleRate > ANDROID_UAC2_DOP_MAX_SAMPLE_RATE_HZ))
        ) {
            AndroidDsdPcmNative.nativeClose(handle)
            throw IOException(when (outputMode) {
                AndroidDsdOutputMode.Pcm -> "The DSD stream has no supported PCM format or exact duration."
                AndroidDsdOutputMode.DoP -> "Android USB DoP supports stereo carriers up to 768 kHz."
            })
        }
        return NativeAndroidFloatPcmDecoder(handle, sampleRate, channelCount, totalFrames, outputEncoding)
    }
}

private const val ANDROID_UAC2_DOP_MAX_SAMPLE_RATE_HZ = 768_000

internal interface AndroidFloatPcmDecoder : AutoCloseable {
    val sampleRateHz: Int
    val channelCount: Int
    val totalFrames: Long
    val pcmEncoding: Int get() = C.ENCODING_PCM_FLOAT

    fun read(destination: ByteArray, capacityFrames: Int): Int
    fun seekToMillis(positionMillis: Long): Int
    fun lastError(): String
}

private class NativeAndroidFloatPcmDecoder(
    private var handle: Long,
    override val sampleRateHz: Int,
    override val channelCount: Int,
    override val totalFrames: Long,
    override val pcmEncoding: Int,
) : AndroidFloatPcmDecoder {
    override fun read(destination: ByteArray, capacityFrames: Int): Int =
        AndroidDsdPcmNative.nativeRead(handle, destination, capacityFrames)

    override fun seekToMillis(positionMillis: Long): Int =
        AndroidDsdPcmNative.nativeSeek(handle, positionMillis)

    override fun lastError(): String = AndroidDsdPcmNative.nativeLastError(handle)

    override fun close() {
        val activeHandle = handle
        handle = 0L
        if (activeHandle != 0L) AndroidDsdPcmNative.nativeClose(activeHandle)
    }
}

/** WAV/RF64 DataSource shared by native DSD-to-PCM and DSD-to-DoP playback. */
@UnstableApi
internal class AndroidFloatPcmWavDataSource(
    private val decoder: AndroidFloatPcmDecoder,
    private val sourceUri: Uri?,
) : DataSource {
    private var wavHeader = ByteArray(0)
    private var headerOffset = 0
    private var pcmFrameBytes = 0
    private var pcmBuffer = ByteArray(0)
    private var pcmBufferOffset = 0
    private var pcmBufferLimit = 0
    private var pendingFrameByteSkip = 0
    private var virtualPosition = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var totalLength = C.LENGTH_UNSET.toLong()
    private var ended = false
    private var opened = false
    private var decoderClosed = false

    override fun addTransferListener(transferListener: TransferListener) = Unit

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long = openVirtualStream(dataSpec.position, dataSpec.length)

    @Throws(IOException::class)
    internal fun openVirtualStream(position: Long = 0L, requestedLength: Long = C.LENGTH_UNSET.toLong()): Long {
        if (decoderClosed || opened) throw IOException("The virtual PCM source is not reusable.")
        try {
            val sampleRate = decoder.sampleRateHz
            val channelCount = decoder.channelCount
            val totalFrames = decoder.totalFrames
            if (sampleRate <= 0 || channelCount !in 1..8 || totalFrames <= 0L) {
                throw IOException("The DSD stream has no supported PCM format or exact duration.")
            }
            val bytesPerSample = when (decoder.pcmEncoding) {
                C.ENCODING_PCM_FLOAT -> Float.SIZE_BYTES
                C.ENCODING_PCM_24BIT -> 3
                else -> throw IOException("The DSD decoder selected an unsupported WAV sample format.")
            }
            pcmFrameBytes = Math.multiplyExact(channelCount, bytesPerSample)
            val sampleDataBytes = Math.multiplyExact(totalFrames, pcmFrameBytes.toLong())
            wavHeader = buildAndroidDsdWavHeader(
                sampleRate,
                channelCount,
                totalFrames,
                sampleDataBytes,
                decoder.pcmEncoding,
            )
            totalLength = Math.addExact(wavHeader.size.toLong(), sampleDataBytes)
            if (position < 0L || position > totalLength) {
                throw IOException("The requested DSD position is outside the decoded stream.")
            }
            virtualPosition = position
            headerOffset = minOf(virtualPosition, wavHeader.size.toLong()).toInt()
            pcmBuffer = ByteArray(2_048 * pcmFrameBytes)
            pcmBufferOffset = 0
            pcmBufferLimit = 0
            pendingFrameByteSkip = 0
            ended = false
            if (virtualPosition > wavHeader.size) {
                seekPcmToByteOffset(virtualPosition - wavHeader.size)
            }
            val available = totalLength - virtualPosition
            bytesRemaining = if (requestedLength == C.LENGTH_UNSET.toLong()) {
                available
            } else {
                minOf(available, requestedLength)
            }
            opened = true
            return bytesRemaining
        } catch (failure: Throwable) {
            close()
            if (failure is IOException) throw failure
            throw IOException("Could not prepare the virtual PCM stream.", failure)
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        require(offset >= 0 && readLength >= 0 && offset + readLength <= buffer.size)
        if (readLength == 0) return 0
        if (!opened || bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        var written = 0
        while (written < readLength && bytesRemaining > 0L) {
            if (headerOffset < wavHeader.size) {
                val count = minOf(readLength - written, wavHeader.size - headerOffset)
                System.arraycopy(wavHeader, headerOffset, buffer, offset + written, count)
                headerOffset += count
                written += count
            } else {
                if (pcmBufferOffset == pcmBufferLimit) fillPcmBuffer()
                if (pcmBufferOffset == pcmBufferLimit) break
                val count = minOf(
                    readLength - written,
                    pcmBufferLimit - pcmBufferOffset,
                    bytesRemaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
                System.arraycopy(pcmBuffer, pcmBufferOffset, buffer, offset + written, count)
                pcmBufferOffset += count
                written += count
            }
            if (written > 0) {
                virtualPosition += written
                bytesRemaining -= written
            }
        }
        if (written > 0) return written
        if (ended && bytesRemaining > 0L) {
            throw IOException("The DSD decoder ended before the declared PCM sample count.")
        }
        return C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = sourceUri

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        if (!decoderClosed) {
            decoderClosed = true
            decoder.close()
        }
        wavHeader = ByteArray(0)
        pcmBuffer = ByteArray(0)
        pcmBufferOffset = 0
        pcmBufferLimit = 0
        pendingFrameByteSkip = 0
        headerOffset = 0
        pcmFrameBytes = 0
        virtualPosition = 0L
        bytesRemaining = C.LENGTH_UNSET.toLong()
        totalLength = C.LENGTH_UNSET.toLong()
        ended = false
        opened = false
    }

    private fun fillPcmBuffer() {
        if (ended || decoderClosed) return
        val capacityFrames = pcmBuffer.size / pcmFrameBytes
        val frames = decoder.read(pcmBuffer, capacityFrames)
        when {
            frames > 0 && frames <= capacityFrames -> {
                pcmBufferOffset = pendingFrameByteSkip
                pendingFrameByteSkip = 0
                pcmBufferLimit = Math.multiplyExact(frames, pcmFrameBytes)
            }
            frames > capacityFrames -> throw IOException("The DSD decoder exceeded its PCM buffer.")
            frames == 0 -> {
                ended = true
                pcmBufferOffset = 0
                pcmBufferLimit = 0
            }
            else -> throw IOException(
                decoder.lastError().ifBlank { "The DSD decoder failed while producing PCM (code $frames)." },
            )
        }
    }

    private fun seekPcmToByteOffset(byteOffset: Long) {
        val targetFrame = byteOffset / pcmFrameBytes
        val subFrameByteOffset = (byteOffset % pcmFrameBytes).toInt()
        val sampleRate = decoder.sampleRateHz
        val seekMillis = Math.multiplyExact(targetFrame, 1_000L) / sampleRate
        val result = decoder.seekToMillis(seekMillis)
        if (result != 0) {
            throw IOException(decoder.lastError().ifBlank {
                "The DSD decoder rejected the requested seek (code $result)."
            })
        }

        // Native DSD seek positions are millisecond based. The WAV seek map addresses exact PCM
        // frames, so discard only the sub-millisecond remainder after the native seek has flushed.
        val firstFrameAtSeek = (seekMillis * sampleRate + 500L) / 1_000L
        var framesToDiscard = (targetFrame - firstFrameAtSeek).coerceAtLeast(0L)
        while (framesToDiscard > 0L) {
            val frames = minOf(framesToDiscard, 2_048L).toInt()
            val read = decoder.read(pcmBuffer, frames)
            if (read <= 0) {
                if (read < 0) {
                    throw IOException(decoder.lastError().ifBlank {
                        "The DSD decoder reached an error while aligning a seek."
                    })
                }
                throw IOException("The DSD decoder ended before the requested PCM seek position.")
            }
            if (read > frames) throw IOException("The DSD decoder exceeded its seek-alignment buffer.")
            framesToDiscard -= read
        }
        pendingFrameByteSkip = subFrameByteOffset
        pcmBufferOffset = 0
        pcmBufferLimit = 0
    }
}

internal data class SeekableDsdFile(
    val descriptor: ParcelFileDescriptor,
    val startOffset: Long,
    val length: Long,
    val temporaryCopy: File? = null,
    val assetDescriptor: AssetFileDescriptor? = null,
)

internal fun openSeekableDsdFile(context: Context, uri: Uri): SeekableDsdFile {
    if (uri.scheme.equals("file", ignoreCase = true)) {
        val file = uri.path?.let(::File) ?: throw IOException("The local DSD file path is invalid.")
        val length = file.length()
        if (length <= 0L) throw IOException("The local DSD file is empty or unavailable.")
        return SeekableDsdFile(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0L, length)
    }

    val asset = context.contentResolver.openAssetFileDescriptor(uri, "r")
        ?: throw IOException("The selected DSD file could not be opened.")
    val descriptor = asset.parcelFileDescriptor
    val startOffset = asset.startOffset
    val statLength = runCatching { Os.fstat(descriptor.fileDescriptor).st_size }.getOrDefault(0L)
    val length = asset.length.takeIf { it >= 0L } ?: (statLength - startOffset)
    val seekable = runCatching {
        Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
    }.isSuccess
    if (seekable && length > 0L) {
        return SeekableDsdFile(descriptor, startOffset, length, assetDescriptor = asset)
    }
    runCatching { asset.close() }

    val copy = File.createTempFile("lazer-dsd-", ".cache", context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            copy.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("The selected DSD file could not be copied to seekable storage.")
        val copyLength = copy.length()
        if (copyLength <= 0L) throw IOException("The selected DSD file is empty.")
        return SeekableDsdFile(
            ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY),
            0L,
            copyLength,
            copy,
        )
    } catch (failure: Throwable) {
        copy.delete()
        throw failure
    }
}

/** Reads exact container duration from the same decoder used during playback. */
internal fun readAndroidDsdDurationMillis(context: Context, uri: Uri): Long? {
    val openedFile = runCatching { openSeekableDsdFile(context, uri) }.getOrNull() ?: return null
    var nativeHandle = 0L
    var durationMillis: Long? = null
    try {
        val outputInfo = LongArray(5)
        nativeHandle = AndroidDsdPcmNative.nativeOpen(
            openedFile.descriptor.fd,
            openedFile.startOffset,
            openedFile.length,
            0,
            false,
            outputInfo,
        )
        if (nativeHandle != 0L) durationMillis = outputInfo[3].takeIf { it > 0L }
    } catch (_: Throwable) {
        // A missing native library or an unreadable provider should not prevent queue import.
    } finally {
        if (nativeHandle != 0L) runCatching { AndroidDsdPcmNative.nativeClose(nativeHandle) }
        runCatching {
            if (openedFile.assetDescriptor != null) openedFile.assetDescriptor.close()
            else openedFile.descriptor.close()
        }
        openedFile.temporaryCopy?.delete()
    }
    return durationMillis
}

internal fun isLocalDsdUri(context: Context, uri: Uri): Boolean {
    if (!uri.scheme.equals("content", ignoreCase = true) &&
        !uri.scheme.equals("file", ignoreCase = true)
    ) return false
    val displayName = if (uri.scheme.equals("content", ignoreCase = true)) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
    } else {
        uri.lastPathSegment
    }
    return isAndroidDsdAudio(displayName, context.contentResolver.getType(uri))
}

internal fun buildAndroidDsdWavHeader(
    sampleRate: Int,
    channels: Int,
    totalFrames: Long,
    sampleDataBytes: Long,
    pcmEncoding: Int = C.ENCODING_PCM_FLOAT,
): ByteArray {
    require(sampleRate > 0 && channels in 1..8 && totalFrames > 0L && sampleDataBytes > 0L)
    val bitsPerSample = when (pcmEncoding) {
        C.ENCODING_PCM_FLOAT -> 32
        C.ENCODING_PCM_24BIT -> 24
        else -> throw IllegalArgumentException("Only float32 PCM and packed PCM24 WAV are supported.")
    }
    val bytesPerSample = bitsPerSample / Byte.SIZE_BITS
    val formatTag = if (pcmEncoding == C.ENCODING_PCM_FLOAT) 3 else 1
    val frameBytes = Math.multiplyExact(channels, bytesPerSample)
    require(sampleDataBytes == Math.multiplyExact(totalFrames, frameBytes.toLong()))
    val useRf64 = sampleDataBytes + 36L > 0xffff_ffffL
    val header = ByteArray(if (useRf64) 80 else 44)
    putFourCc(header, 0, if (useRf64) "RF64" else "RIFF")
    putLe32(header, 4, if (useRf64) 0xffff_ffffL else 36L + sampleDataBytes)
    putFourCc(header, 8, "WAVE")
    var formatOffset = 12
    if (useRf64) {
        putFourCc(header, 12, "ds64")
        putLe32(header, 16, 28L)
        putLe64(header, 20, header.size.toLong() + sampleDataBytes - 8L)
        putLe64(header, 28, sampleDataBytes)
        putLe64(header, 36, totalFrames)
        putLe32(header, 44, 0L)
        formatOffset = 48
    }
    putFourCc(header, formatOffset, "fmt ")
    putLe32(header, formatOffset + 4, 16L)
    putLe16(header, formatOffset + 8, formatTag)
    putLe16(header, formatOffset + 10, channels)
    putLe32(header, formatOffset + 12, sampleRate.toLong())
    putLe32(header, formatOffset + 16, sampleRate.toLong() * frameBytes)
    putLe16(header, formatOffset + 20, frameBytes)
    putLe16(header, formatOffset + 22, bitsPerSample)
    putFourCc(header, formatOffset + 24, "data")
    putLe32(header, formatOffset + 28, if (useRf64) 0xffff_ffffL else sampleDataBytes)
    return header
}

private fun putFourCc(destination: ByteArray, offset: Int, value: String) {
    value.encodeToByteArray().copyInto(destination, offset)
}

private fun putLe16(destination: ByteArray, offset: Int, value: Int) {
    destination[offset] = value.toByte()
    destination[offset + 1] = (value ushr 8).toByte()
}

private fun putLe32(destination: ByteArray, offset: Int, value: Long) {
    for (index in 0 until 4) destination[offset + index] = (value ushr (index * 8)).toByte()
}

private fun putLe64(destination: ByteArray, offset: Int, value: Long) {
    for (index in 0 until 8) destination[offset + index] = (value ushr (index * 8)).toByte()
}

internal object AndroidDsdPcmNative {
    private val loaded = AtomicBoolean(false)

    private fun ensureLoaded() {
        if (loaded.compareAndSet(false, true)) {
            try {
                System.loadLibrary("lazer-audio-dsd")
            } catch (failure: Throwable) {
                loaded.set(false)
                throw failure
            }
        }
    }

    @JvmStatic
    external fun nativeOpen(
        fileDescriptor: Int,
        startOffset: Long,
        length: Long,
        targetSampleRate: Int,
        doP: Boolean,
        outputInfo: LongArray,
    ): Long

    @JvmStatic
    external fun nativeRead(handle: Long, destination: ByteArray, capacityFrames: Int): Int

    @JvmStatic
    external fun nativeSeek(handle: Long, positionMillis: Long): Int

    @JvmStatic
    external fun nativeSampleRate(handle: Long): Int

    @JvmStatic
    external fun nativeLastError(handle: Long): String

    @JvmStatic
    external fun nativeClose(handle: Long)

    init {
        ensureLoaded()
    }
}
