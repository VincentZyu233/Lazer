package dev.naominet.lazer

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Snapshot of direct ALSA hardware playback PCMs; plugin routes are intentionally omitted. */
internal object DesktopAlsaOutputDeviceCatalog {
    private const val MAX_DEVICES = 4096
    private const val MAX_STRING_BYTES = 32_768
    private const val BUFFER_RETRIES = 3

    fun enumerate(): List<DesktopAlsaOutputDevice> {
        val api = LazerAudioLoader.library ?: throw DesktopAlsaCatalogUnavailableException()
        val catalogRef = PointerByReference()
        val createStatus = optionalApiCall {
            api.lazer_audio_output_device_catalog_create(catalogRef)
        }
        val catalog = catalogRef.value

        var failure: Throwable? = null
        try {
            checkStatus("create catalog", createStatus)
            val handle = catalog ?: throw DesktopAlsaCatalogException(
                operation = "create catalog",
                errorCode = LAZER_AUDIO_ERROR_DEVICE,
                detail = "native API returned a null catalog",
            )
            val countRef = IntByReference()
            checkStatus(
                "read catalog count",
                optionalApiCall { api.lazer_audio_output_device_catalog_count(handle, countRef) },
            )
            val count = countRef.value
            if (count !in 0..MAX_DEVICES) {
                throw DesktopAlsaCatalogException(
                    operation = "read catalog count",
                    errorCode = LAZER_AUDIO_ERROR_DEVICE,
                    detail = "invalid ALSA PCM count: $count",
                )
            }
            return List(count) { index -> readDevice(api, handle, index) }
        } catch (error: Throwable) {
            val mapped = mapOptionalApiFailure(error)
            failure = mapped
            throw mapped
        } finally {
            if (catalog != null) {
                try {
                    optionalApiCall { api.lazer_audio_output_device_catalog_destroy(catalog) }
                } catch (releaseError: Throwable) {
                    val mapped = mapOptionalApiFailure(releaseError)
                    if (failure != null) failure.addSuppressed(mapped) else throw mapped
                }
            }
        }
    }

    private fun readDevice(
        api: LazerAudioLibrary,
        catalog: Pointer,
        index: Int,
    ): DesktopAlsaOutputDevice {
        var capacities = queryCapacities(api, catalog, index)
        repeat(BUFFER_RETRIES + 1) { attempt ->
            capacities.validate(index)
            val token = Memory(capacities.deviceTokenBytes.toLong())
            val identity = Memory(capacities.identityKeyBytes.toLong())
            val name = Memory(capacities.displayNameBytes.toLong())
            try {
                val info = LazerAudioOutputDeviceInfo().apply {
                    structSize = size()
                    write()
                }
                val tokenBytes = IntByReference()
                val identityBytes = IntByReference()
                val nameBytes = IntByReference()
                val status = optionalApiCall {
                    api.lazer_audio_output_device_catalog_get(
                        catalog,
                        index,
                        info,
                        token,
                        capacities.deviceTokenBytes,
                        tokenBytes,
                        identity,
                        capacities.identityKeyBytes,
                        identityBytes,
                        name,
                        capacities.displayNameBytes,
                        nameBytes,
                    )
                }
                info.read()
                if (status == LAZER_AUDIO_OK) {
                    return DesktopAlsaOutputDevice(
                        deviceToken = decodeUtf8(token, tokenBytes.value, "device token", index),
                        identityKey = decodeUtf8(identity, identityBytes.value, "identity key", index),
                        displayName = decodeUtf8(name, nameBytes.value, "display name", index),
                        flags = info.flags,
                    )
                }
                if (status != LAZER_AUDIO_ERROR_BUFFER_TOO_SMALL) {
                    checkStatus("read device $index", status)
                }
                val reported = BufferCapacities(
                    tokenBytes.value,
                    identityBytes.value,
                    nameBytes.value,
                ).validate(index)
                capacities = capacities.growTo(reported)
                if (attempt == BUFFER_RETRIES) {
                    throw DesktopAlsaCatalogException(
                        operation = "read device $index",
                        errorCode = LAZER_AUDIO_ERROR_BUFFER_TOO_SMALL,
                        detail = "ALSA device strings kept changing while copying",
                    )
                }
            } finally {
                token.close()
                identity.close()
                name.close()
            }
        }
        throw DesktopAlsaCatalogException(
            operation = "read device $index",
            errorCode = LAZER_AUDIO_ERROR_BUFFER_TOO_SMALL,
        )
    }

    private fun queryCapacities(
        api: LazerAudioLibrary,
        catalog: Pointer,
        index: Int,
    ): BufferCapacities {
        val info = LazerAudioOutputDeviceInfo().apply {
            structSize = size()
            write()
        }
        val tokenBytes = IntByReference()
        val identityBytes = IntByReference()
        val nameBytes = IntByReference()
        val status = optionalApiCall {
            api.lazer_audio_output_device_catalog_get(
                catalog,
                index,
                info,
                null,
                0,
                tokenBytes,
                null,
                0,
                identityBytes,
                null,
                0,
                nameBytes,
            )
        }
        info.read()
        if (status != LAZER_AUDIO_ERROR_BUFFER_TOO_SMALL && status != LAZER_AUDIO_OK) {
            checkStatus("query device $index string lengths", status)
        }
        return BufferCapacities(tokenBytes.value, identityBytes.value, nameBytes.value)
            .validate(index)
    }

    private fun decodeUtf8(memory: Memory, byteCount: Int, field: String, index: Int): String {
        if (byteCount !in 1..MAX_STRING_BYTES) {
            throw DesktopAlsaCatalogException(
                operation = "read device $index",
                errorCode = LAZER_AUDIO_ERROR_DEVICE,
                detail = "invalid NUL-inclusive UTF-8 byte count for $field: $byteCount",
            )
        }
        val bytes = memory.getByteArray(0, byteCount)
        if (bytes.last() != 0.toByte()) {
            throw DesktopAlsaCatalogException(
                operation = "read device $index",
                errorCode = LAZER_AUDIO_ERROR_DEVICE,
                detail = "$field is not NUL-terminated",
            )
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, byteCount - 1))
                .toString()
        } catch (error: Exception) {
            throw DesktopAlsaCatalogException(
                operation = "read device $index",
                errorCode = LAZER_AUDIO_ERROR_DEVICE,
                detail = "$field is not valid UTF-8",
                cause = error,
            )
        }
    }

    private fun checkStatus(operation: String, status: Int) {
        if (status == LAZER_AUDIO_OK) return
        val detail = when (status) {
            LAZER_AUDIO_ERROR_INDEX_OUT_OF_RANGE -> "ALSA device index disappeared from this snapshot"
            LAZER_AUDIO_ERROR_BUFFER_TOO_SMALL -> "native string buffer was too small"
            else -> null
        }
        throw DesktopAlsaCatalogException(operation, status, detail)
    }

    private inline fun <T> optionalApiCall(call: () -> T): T = try {
        call()
    } catch (error: UnsatisfiedLinkError) {
        throw DesktopAlsaCatalogUnavailableException(error)
    }

    private fun mapOptionalApiFailure(error: Throwable): Throwable = when (error) {
        is UnsatisfiedLinkError -> DesktopAlsaCatalogUnavailableException(error)
        else -> error
    }

    private data class BufferCapacities(
        val deviceTokenBytes: Int,
        val identityKeyBytes: Int,
        val displayNameBytes: Int,
    ) {
        fun validate(index: Int): BufferCapacities {
            if (deviceTokenBytes !in 1..MAX_STRING_BYTES ||
                identityKeyBytes !in 1..MAX_STRING_BYTES ||
                displayNameBytes !in 1..MAX_STRING_BYTES
            ) {
                throw DesktopAlsaCatalogException(
                    operation = "query device $index string lengths",
                    errorCode = LAZER_AUDIO_ERROR_DEVICE,
                    detail = "invalid NUL-inclusive UTF-8 byte counts: $this",
                )
            }
            return this
        }

        fun growTo(reported: BufferCapacities) = BufferCapacities(
            maxOf(deviceTokenBytes, reported.deviceTokenBytes),
            maxOf(identityKeyBytes, reported.identityKeyBytes),
            maxOf(displayNameBytes, reported.displayNameBytes),
        )
    }
}

internal class DesktopAlsaCatalogUnavailableException(cause: Throwable? = null) :
    IllegalStateException("The loaded audio library does not provide the ALSA output catalog API.", cause)

internal class DesktopAlsaCatalogException(
    val operation: String,
    val errorCode: Int,
    detail: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(
    buildString {
        append("ALSA output catalog failed during ")
        append(operation)
        append(" (error ")
        append(errorCode)
        append(')')
        if (detail != null) {
            append(": ")
            append(detail)
        }
    },
    cause,
)
