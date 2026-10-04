package dev.naominet.lazer

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Stable CoreAudio device UID catalog, backed by the native HAL snapshot API. */
internal object DesktopCoreAudioOutputDeviceCatalog {
    private const val MAX_DEVICES = 4096
    private const val MAX_STRING_BYTES = 32_768

    fun enumerate(): List<DesktopCoreAudioOutputDevice> {
        val api = LazerAudioLoader.library ?: throw DesktopCoreAudioCatalogUnavailableException()
        val catalogRef = PointerByReference()
        val createStatus = optionalApiCall {
            api.lazer_audio_output_device_catalog_create(catalogRef)
        }
        val catalog = catalogRef.value
        var failure: Throwable? = null
        try {
            checkStatus("create catalog", createStatus)
            val handle = catalog ?: throw DesktopCoreAudioCatalogException(
                "create catalog", LAZER_AUDIO_ERROR_DEVICE, "native API returned a null catalog",
            )
            val count = IntByReference()
            checkStatus("read catalog count", optionalApiCall {
                api.lazer_audio_output_device_catalog_count(handle, count)
            })
            if (count.value !in 0..MAX_DEVICES) {
                throw DesktopCoreAudioCatalogException(
                    "read catalog count", LAZER_AUDIO_ERROR_DEVICE,
                    "invalid CoreAudio device count: ${count.value}",
                )
            }
            return List(count.value) { index -> readDevice(api, handle, index) }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (catalog != null) {
                try {
                    optionalApiCall { api.lazer_audio_output_device_catalog_destroy(catalog) }
                } catch (releaseError: Throwable) {
                    if (failure != null) failure.addSuppressed(releaseError) else throw releaseError
                }
            }
        }
    }

    private fun readDevice(api: LazerAudioLibrary, catalog: Pointer, index: Int): DesktopCoreAudioOutputDevice {
        val info = LazerAudioOutputDeviceInfo().apply {
            structSize = size()
            write()
        }
        val tokenBytes = IntByReference()
        val identityBytes = IntByReference()
        val nameBytes = IntByReference()
        val queryStatus = optionalApiCall {
            api.lazer_audio_output_device_catalog_get(
                catalog, index, info,
                null, 0, tokenBytes,
                null, 0, identityBytes,
                null, 0, nameBytes,
            )
        }
        info.read()
        if (queryStatus != LAZER_AUDIO_ERROR_BUFFER_TOO_SMALL && queryStatus != LAZER_AUDIO_OK) {
            checkStatus("query device $index string lengths", queryStatus)
        }
        val sizes = listOf(tokenBytes.value, identityBytes.value, nameBytes.value)
        if (sizes.any { it !in 1..MAX_STRING_BYTES }) {
            throw DesktopCoreAudioCatalogException(
                "query device $index string lengths", LAZER_AUDIO_ERROR_DEVICE,
                "invalid NUL-inclusive UTF-8 byte counts: $sizes",
            )
        }

        val token = Memory(tokenBytes.value.toLong())
        val identity = Memory(identityBytes.value.toLong())
        val name = Memory(nameBytes.value.toLong())
        try {
            val tokenRequired = IntByReference()
            val identityRequired = IntByReference()
            val nameRequired = IntByReference()
            val status = optionalApiCall {
                api.lazer_audio_output_device_catalog_get(
                    catalog, index, info,
                    token, tokenBytes.value, tokenRequired,
                    identity, identityBytes.value, identityRequired,
                    name, nameBytes.value, nameRequired,
                )
            }
            checkStatus("read device $index", status)
            info.read()
            return DesktopCoreAudioOutputDevice(
                deviceToken = decode(token, tokenRequired.value, "device UID", index),
                identityKey = decode(identity, identityRequired.value, "identity key", index),
                displayName = decode(name, nameRequired.value, "display name", index),
                flags = info.flags,
            )
        } finally {
            token.close()
            identity.close()
            name.close()
        }
    }

    private fun decode(memory: Memory, byteCount: Int, field: String, index: Int): String {
        if (byteCount !in 1..MAX_STRING_BYTES) {
            throw DesktopCoreAudioCatalogException(
                "read device $index", LAZER_AUDIO_ERROR_DEVICE,
                "invalid NUL-inclusive byte count for $field: $byteCount",
            )
        }
        val bytes = memory.getByteArray(0, byteCount)
        if (bytes.last() != 0.toByte()) {
            throw DesktopCoreAudioCatalogException(
                "read device $index", LAZER_AUDIO_ERROR_DEVICE, "$field is not NUL-terminated",
            )
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, byteCount - 1))
                .toString()
        } catch (error: Exception) {
            throw DesktopCoreAudioCatalogException(
                "read device $index", LAZER_AUDIO_ERROR_DEVICE, "$field is not valid UTF-8", error,
            )
        }
    }

    private fun checkStatus(operation: String, status: Int) {
        if (status == LAZER_AUDIO_OK) return
        throw DesktopCoreAudioCatalogException(operation, status)
    }

    private inline fun <T> optionalApiCall(call: () -> T): T = try {
        call()
    } catch (error: UnsatisfiedLinkError) {
        throw DesktopCoreAudioCatalogUnavailableException(error)
    }
}

internal class DesktopCoreAudioCatalogUnavailableException(cause: Throwable? = null) :
    IllegalStateException("The loaded audio library does not provide the CoreAudio output catalog API.", cause)

internal class DesktopCoreAudioCatalogException(
    val operation: String,
    val errorCode: Int,
    detail: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(
    buildString {
        append("CoreAudio output catalog failed during ")
        append(operation)
        append(" (error ")
        append(errorCode)
        append(')')
        if (detail != null) append(": ").append(detail)
    },
    cause,
)
