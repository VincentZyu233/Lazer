package dev.naominet.lazer

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/** One Windows WASAPI endpoint and the key used to re-resolve it after device changes. */
internal data class DesktopWasapiDevice(
    val endpointId: String,
    override val identityKey: String,
    val friendlyName: String,
    val endpointState: Int,
    val defaultRoleMask: Int,
    override val stableIdentity: Boolean,
    val endpointVolumeQueryHresult: Int = WASAPI_ENDPOINT_VOLUME_QUERY_NOT_RUN,
    val endpointVolumeHardwareSupportFlags: Int = 0,
) : DesktopAudioOutputDevice {
    override val deviceToken: String
        get() = endpointId
    override val displayName: String
        get() = friendlyName
    override val active: Boolean
        get() = endpointState and WASAPI_DEVICE_STATE_ACTIVE != 0
    override val isDefault: Boolean
        get() = defaultRoleMask != 0
    override val backendName: String = "WASAPI"
}

internal data class DesktopWasapiOutputSelection(
    val endpointId: String?,
    val unavailable: Boolean,
)

/** Resolves saved identity without falling through to the system default when a selected DAC vanished. */
internal fun resolveDesktopWasapiOutputSelection(
    identityKey: String?,
    devices: List<DesktopWasapiDevice>,
): DesktopWasapiOutputSelection {
    if (identityKey == null) return DesktopWasapiOutputSelection(endpointId = null, unavailable = false)
    val selected = devices.firstOrNull {
        it.identityKey == identityKey && it.endpointState and WASAPI_DEVICE_STATE_ACTIVE != 0
    }
    return DesktopWasapiOutputSelection(
        endpointId = selected?.endpointId,
        unavailable = selected == null,
    )
}

internal const val WASAPI_DEVICE_STATE_ACTIVE = 0x1
internal const val WASAPI_ENDPOINT_VOLUME_QUERY_NOT_RUN = Int.MIN_VALUE
internal const val WASAPI_ENDPOINT_HARDWARE_SUPPORT_VOLUME = 0x1

internal val DesktopWasapiDevice.endpointVolumeQuerySucceeded: Boolean
    get() = endpointVolumeQueryHresult >= 0

internal val DesktopWasapiDevice.reportsHardwareEndpointVolume: Boolean
    get() = endpointVolumeQuerySucceeded &&
        endpointVolumeHardwareSupportFlags and WASAPI_ENDPOINT_HARDWARE_SUPPORT_VOLUME != 0

/**
 * Enumerates WASAPI endpoints through the optional catalog ABI. The catalog is a snapshot and must
 * be destroyed after use; callers persist [DesktopWasapiDevice.identityKey] and resolve it against
 * a fresh enumeration before passing [DesktopWasapiDevice.endpointId] to playback.
 */
internal object DesktopWasapiDeviceCatalog {
    private const val ERROR_BUFFER_TOO_SMALL = -9
    private const val ERROR_INDEX_OUT_OF_RANGE = -10
    private const val IDENTITY_KIND_ENDPOINT = 0
    private const val IDENTITY_KIND_STABLE = 1
    private const val MAX_ENDPOINTS = 4096
    private const val MAX_STRING_CHARS = 32768
    private const val BUFFER_RETRIES = 3

    fun enumerate(): List<DesktopWasapiDevice> {
        val api = LazerAudioLoader.library ?: throw DesktopWasapiCatalogUnavailableException()
        val catalogRef = PointerByReference()
        val createStatus = optionalApiCall { api.lazer_audio_device_catalog_create(catalogRef) }
        val catalog = catalogRef.value

        var failure: Throwable? = null
        try {
            checkStatus("create catalog", createStatus)
            val handle = catalog ?: throw DesktopWasapiCatalogException(
                operation = "create catalog",
                errorCode = LAZER_AUDIO_ERROR_DEVICE,
                detail = "native API returned a null catalog",
            )

            val countRef = IntByReference(0)
            checkStatus(
                "read catalog count",
                optionalApiCall { api.lazer_audio_device_catalog_count(handle, countRef) },
            )
            val count = countRef.value
            if (count < 0 || count > MAX_ENDPOINTS) {
                throw DesktopWasapiCatalogException(
                    operation = "read catalog count",
                    errorCode = LAZER_AUDIO_ERROR_DEVICE,
                    detail = "invalid endpoint count: $count",
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
                    optionalApiCall { api.lazer_audio_device_catalog_destroy(catalog) }
                } catch (releaseError: Throwable) {
                    val mapped = mapOptionalApiFailure(releaseError)
                    if (failure != null) {
                        failure.addSuppressed(mapped)
                    } else {
                        throw mapped
                    }
                }
            }
        }
    }

    private fun readDevice(
        api: LazerAudioLibrary,
        catalog: Pointer,
        index: Int,
    ): DesktopWasapiDevice {
        var capacities = BufferCapacities(0, 0, 0)

        repeat(BUFFER_RETRIES + 1) { attempt ->
            val info = LazerAudioDeviceInfo().apply {
                structSize = size()
                write()
            }
            val endpointChars = IntByReference(0)
            val identityChars = IntByReference(0)
            val nameChars = IntByReference(0)

            // First call is the size query. The native function returns -9 and reports NUL-inclusive
            // wchar_t counts when these null/zero-capacity buffers cannot hold its strings.
            val queryStatus = optionalApiCall {
                api.lazer_audio_device_catalog_get(
                    catalog,
                    index,
                    info,
                    null,
                    0,
                    endpointChars,
                    null,
                    0,
                    identityChars,
                    null,
                    0,
                    nameChars,
                )
            }
            info.read()
            if (queryStatus != ERROR_BUFFER_TOO_SMALL && queryStatus != LAZER_AUDIO_OK) {
                checkStatus("query endpoint $index strings", queryStatus)
            }
            capacities = BufferCapacities(
                endpointChars.value,
                identityChars.value,
                nameChars.value,
            ).validated(index)

            val endpointBuffer = wideBuffer(capacities.endpointIdChars)
            val identityBuffer = wideBuffer(capacities.identityKeyChars)
            val nameBuffer = wideBuffer(capacities.friendlyNameChars)
            try {
                info.structSize = info.size()
                info.write()
                endpointChars.value = 0
                identityChars.value = 0
                nameChars.value = 0
                val copyStatus = optionalApiCall {
                    api.lazer_audio_device_catalog_get(
                        catalog,
                        index,
                        info,
                        endpointBuffer,
                        capacities.endpointIdChars,
                        endpointChars,
                        identityBuffer,
                        capacities.identityKeyChars,
                        identityChars,
                        nameBuffer,
                        capacities.friendlyNameChars,
                        nameChars,
                    )
                }
                info.read()
                if (copyStatus == LAZER_AUDIO_OK) {
                    validateIdentityKind(index, info.identityKind)
                    return DesktopWasapiDevice(
                        endpointId = readWideString(endpointBuffer, capacities.endpointIdChars, "endpoint ID", index),
                        identityKey = readWideString(identityBuffer, capacities.identityKeyChars, "identity key", index),
                        friendlyName = readWideString(nameBuffer, capacities.friendlyNameChars, "friendly name", index),
                        endpointState = info.endpointState,
                        defaultRoleMask = info.defaultRoleMask,
                        stableIdentity = info.identityKind == IDENTITY_KIND_STABLE,
                        endpointVolumeQueryHresult = info.endpointVolumeQueryHresult,
                        endpointVolumeHardwareSupportFlags = info.endpointVolumeHardwareSupportFlags,
                    )
                }
                if (copyStatus != ERROR_BUFFER_TOO_SMALL) {
                    checkStatus("read endpoint $index", copyStatus)
                }

                val reported = BufferCapacities(
                    endpointChars.value,
                    identityChars.value,
                    nameChars.value,
                ).validated(index)
                capacities = capacities.growTo(reported)
                if (attempt == BUFFER_RETRIES) {
                    throw DesktopWasapiCatalogException(
                        operation = "read endpoint $index",
                        errorCode = ERROR_BUFFER_TOO_SMALL,
                        detail = "string lengths kept changing while copying",
                    )
                }
            } finally {
                endpointBuffer.close()
                identityBuffer.close()
                nameBuffer.close()
            }
        }

        throw DesktopWasapiCatalogException(
            operation = "read endpoint $index",
            errorCode = ERROR_BUFFER_TOO_SMALL,
        )
    }

    private fun validateIdentityKind(index: Int, identityKind: Int) {
        if (identityKind != IDENTITY_KIND_ENDPOINT && identityKind != IDENTITY_KIND_STABLE) {
            throw DesktopWasapiCatalogException(
                operation = "read endpoint $index",
                errorCode = LAZER_AUDIO_ERROR_DEVICE,
                detail = "unknown identity kind: $identityKind",
            )
        }
    }

    private fun wideBuffer(capacityChars: Int): Memory =
        Memory(capacityChars.toLong() * Native.WCHAR_SIZE.toLong())

    private fun readWideString(buffer: Memory, capacityChars: Int, field: String, index: Int): String {
        val value = buffer.getWideString(0)
        if (value.length >= capacityChars) {
            throw DesktopWasapiCatalogException(
                operation = "read endpoint $index",
                errorCode = LAZER_AUDIO_ERROR_DEVICE,
                detail = "$field was not NUL-terminated within its reported capacity",
            )
        }
        return value
    }

    private fun checkStatus(operation: String, status: Int) {
        if (status == LAZER_AUDIO_OK) return
        val detail = when (status) {
            ERROR_INDEX_OUT_OF_RANGE -> "endpoint index no longer exists in this catalog snapshot"
            ERROR_BUFFER_TOO_SMALL -> "native string buffer was too small"
            else -> null
        }
        throw DesktopWasapiCatalogException(operation, status, detail)
    }

    private inline fun <T> optionalApiCall(call: () -> T): T = try {
        call()
    } catch (error: UnsatisfiedLinkError) {
        throw DesktopWasapiCatalogUnavailableException(error)
    }

    private fun mapOptionalApiFailure(error: Throwable): Throwable = when (error) {
        is UnsatisfiedLinkError -> DesktopWasapiCatalogUnavailableException(error)
        else -> error
    }

    private data class BufferCapacities(
        val endpointIdChars: Int,
        val identityKeyChars: Int,
        val friendlyNameChars: Int,
    ) {
        fun validated(index: Int): BufferCapacities {
            if (endpointIdChars !in 1..MAX_STRING_CHARS ||
                identityKeyChars !in 1..MAX_STRING_CHARS ||
                friendlyNameChars !in 1..MAX_STRING_CHARS
            ) {
                throw DesktopWasapiCatalogException(
                    operation = "query endpoint $index strings",
                    errorCode = LAZER_AUDIO_ERROR_DEVICE,
                    detail = "invalid NUL-inclusive string lengths: $this",
                )
            }
            return this
        }

        fun growTo(reported: BufferCapacities): BufferCapacities = BufferCapacities(
            endpointIdChars = maxOf(endpointIdChars, reported.endpointIdChars),
            identityKeyChars = maxOf(identityKeyChars, reported.identityKeyChars),
            friendlyNameChars = maxOf(friendlyNameChars, reported.friendlyNameChars),
        )
    }
}

internal class DesktopWasapiCatalogUnavailableException(cause: Throwable? = null) :
    IllegalStateException("The loaded audio DLL does not provide the optional WASAPI device catalog API.", cause)

internal class DesktopWasapiCatalogException(
    val operation: String,
    val errorCode: Int,
    detail: String? = null,
) : IllegalStateException(
    buildString {
        append("WASAPI device catalog failed during ")
        append(operation)
        append(" (error ")
        append(errorCode)
        append(')')
        if (detail != null) {
            append(": ")
            append(detail)
        }
    },
)
