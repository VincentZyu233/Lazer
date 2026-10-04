package dev.naominet.lazer

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Build

/**
 * AudioDeviceInfo IDs are session-local. Prefer the OS address; fall back to a unique product
 * name/type pair, which the route chooser resolves only when that identity is unambiguous.
 */
internal fun stableUsbAudioTargetIdentity(type: Int, address: String, productName: String): String? {
    val normalizedAddress = address.trim()
    val normalizedName = productName.trim()
    if (normalizedAddress.isNotEmpty()) {
        val namePart = normalizedName.takeIf(String::isNotEmpty)?.let { "/name:$it" }.orEmpty()
        return "type:$type/address:$normalizedAddress$namePart"
    }
    if (normalizedName.isEmpty()) return null
    return "type:$type/name:$normalizedName"
}

internal fun usbAudioAddressOrEmpty(device: AudioDeviceInfo): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address.orEmpty() else ""

internal class AndroidUsbAudioTargetStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val selectedIdentity: String?
        get() = preferences.getString(KEY_SELECTED_IDENTITY, null)?.takeIf(String::isNotBlank)

    val selectedLabel: String?
        get() = preferences.getString(KEY_SELECTED_LABEL, null)?.takeIf(String::isNotBlank)

    fun select(identity: String?, label: String?) {
        preferences.edit().apply {
            if (identity.isNullOrBlank()) {
                remove(KEY_SELECTED_IDENTITY)
                remove(KEY_SELECTED_LABEL)
            } else {
                putString(KEY_SELECTED_IDENTITY, identity)
                if (label.isNullOrBlank()) remove(KEY_SELECTED_LABEL) else putString(KEY_SELECTED_LABEL, label)
            }
        }.apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "lazer.android.audio-output"
        const val KEY_SELECTED_IDENTITY = "usb_target_identity"
        const val KEY_SELECTED_LABEL = "usb_target_label"
    }
}
