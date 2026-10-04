package dev.naominet.lazer

import com.sun.jna.WString
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.IntByReference

/** Current Windows endpoint master volume, which can affect every application using that endpoint. */
internal data class DesktopWasapiEndpointVolumeState(
    val scalar: Float,
    val hardwareSupportFlags: Int,
)

internal class DesktopWasapiEndpointVolumeException(
    val errorCode: Int,
    val nativeHresult: Int,
) : IllegalStateException(
    when (errorCode) {
        LAZER_AUDIO_ERROR_UNSUPPORTED -> "Windows did not report hardware endpoint volume support."
        else -> "Windows endpoint volume operation failed (HRESULT 0x${nativeHresult.toUInt().toString(16)})."
    },
)

/**
 * Windows endpoint master volume path. Native calls re-check QueryHardwareSupport before every
 * read/write and never fall back to a software endpoint-volume implementation.
 */
internal object DesktopWasapiEndpointVolume {
    fun read(endpointId: String): DesktopWasapiEndpointVolumeState {
        require(endpointId.isNotBlank())
        val api = LazerAudioLoader.library ?: throw DesktopWasapiEndpointVolumeException(
            LAZER_AUDIO_ERROR_DEVICE,
            0,
        )
        val scalar = FloatByReference()
        val flags = IntByReference()
        val hresult = IntByReference()
        val status = api.lazer_audio_endpoint_volume_get(
            WString(endpointId), scalar, flags, hresult,
        )
        checkStatus(status, hresult.value)
        validateHardwareVolume(flags.value, hresult.value)
        val value = scalar.value
        if (!value.isFinite() || value !in 0f..1f) {
            throw DesktopWasapiEndpointVolumeException(LAZER_AUDIO_ERROR_DEVICE, hresult.value)
        }
        return DesktopWasapiEndpointVolumeState(value, flags.value)
    }

    fun write(endpointId: String, scalar: Float) {
        require(endpointId.isNotBlank())
        require(scalar.isFinite() && scalar in 0f..1f)
        val api = LazerAudioLoader.library ?: throw DesktopWasapiEndpointVolumeException(
            LAZER_AUDIO_ERROR_DEVICE,
            0,
        )
        val flags = IntByReference()
        val hresult = IntByReference()
        val status = api.lazer_audio_endpoint_volume_set(
            WString(endpointId), scalar, flags, hresult,
        )
        checkStatus(status, hresult.value)
        validateHardwareVolume(flags.value, hresult.value)
    }

    private fun checkStatus(status: Int, hresult: Int) {
        if (status != LAZER_AUDIO_OK) {
            throw DesktopWasapiEndpointVolumeException(status, hresult)
        }
    }

    private fun validateHardwareVolume(flags: Int, hresult: Int) {
        if (hresult < 0 || flags and WASAPI_ENDPOINT_HARDWARE_SUPPORT_VOLUME == 0) {
            throw DesktopWasapiEndpointVolumeException(LAZER_AUDIO_ERROR_UNSUPPORTED, hresult)
        }
    }
}
