package dev.naominet.lazer

import android.hardware.usb.UsbDeviceConnection

internal enum class AndroidUacVersion {
    Uac1,
    Uac2,
}

/** A master-channel Feature Unit directly upstream of a physical playback terminal. */
internal data class AndroidUacVolumeControl(
    val version: AndroidUacVersion,
    val controlInterfaceNumber: Int,
    val unitId: Int,
    val channelNumber: Int = 0,
)

internal data class AndroidUsbControlRequest(
    val requestType: Int,
    val request: Int,
    val value: Int,
    val index: Int,
    val data: ByteArray,
    val length: Int,
)

internal data class AndroidUacVolumeRange(
    val minimumDb256: Int,
    val maximumDb256: Int,
    val resolutionDb256: Int,
) {
    init {
        require(minimumDb256 > Short.MIN_VALUE) { "UAC RANGE minimum cannot be the silence sentinel" }
        require(maximumDb256 >= minimumDb256) { "UAC RANGE minimum must not exceed its maximum" }
        require(resolutionDb256 > 0) { "UAC RANGE resolution must be positive" }
        require((maximumDb256 - minimumDb256) % resolutionDb256 == 0) {
            "UAC RANGE maximum must lie on the advertised resolution grid"
        }
    }

    fun contains(db256: Int): Boolean =
        db256 in minimumDb256..maximumDb256 && (db256 - minimumDb256) % resolutionDb256 == 0
}

internal sealed interface AndroidUacVolumeValue {
    data object Muted : AndroidUacVolumeValue
    data class Finite(val db256: Int) : AndroidUacVolumeValue
}

internal enum class AndroidUacVolumeDirection { Down, Up }

/** Accepts only the permission broadcast created for the request that is still awaiting a result. */
internal fun androidUacPermissionCallbackMatches(
    requestDeviceId: String,
    requestVendorId: Int,
    requestProductId: Int,
    requestGeneration: Long,
    currentGeneration: Long,
    selectedDeviceId: String?,
    callbackDeviceId: String?,
    callbackVendorId: Int,
    callbackProductId: Int,
    callbackGeneration: Long?,
): Boolean = requestGeneration == currentGeneration &&
    requestDeviceId == selectedDeviceId && requestDeviceId == callbackDeviceId &&
    requestVendorId == callbackVendorId && requestProductId == callbackProductId &&
    requestGeneration == callbackGeneration

/** Moves by about one dB, snapping to a valid value advertised by the USB device. */
internal fun nextAndroidUacVolumeValue(
    current: AndroidUacVolumeValue,
    ranges: List<AndroidUacVolumeRange>,
    direction: AndroidUacVolumeDirection,
): Int? {
    if (ranges.isEmpty()) return null
    if (current == AndroidUacVolumeValue.Muted) {
        return if (direction == AndroidUacVolumeDirection.Up) ranges.first().minimumDb256 else null
    }
    val currentDb256 = (current as? AndroidUacVolumeValue.Finite)?.db256 ?: return null
    val rangeIndex = ranges.indexOfFirst { currentDb256 in it.minimumDb256..it.maximumDb256 }
    if (rangeIndex < 0) return null

    val range = ranges[rangeIndex]
    val increments = ((256L + range.resolutionDb256 - 1) / range.resolutionDb256)
        .coerceAtLeast(1)
    val delta = increments * range.resolutionDb256
    val candidate = when (direction) {
        AndroidUacVolumeDirection.Up -> currentDb256.toLong() + delta
        AndroidUacVolumeDirection.Down -> currentDb256.toLong() - delta
    }
    if (candidate in range.minimumDb256.toLong()..range.maximumDb256.toLong()) {
        val offset = candidate - range.minimumDb256
        val snapped = range.minimumDb256 +
            (((offset + range.resolutionDb256 / 2) / range.resolutionDb256) * range.resolutionDb256).toInt()
        return snapped.takeIf(range::contains)
    }
    val adjacent = when (direction) {
        AndroidUacVolumeDirection.Up -> ranges.getOrNull(rangeIndex + 1)
        AndroidUacVolumeDirection.Down -> ranges.getOrNull(rangeIndex - 1)
    } ?: return null
    return if (direction == AndroidUacVolumeDirection.Up) adjacent.minimumDb256 else adjacent.maximumDb256
}

/**
 * Parses the descriptor stream returned by UsbDeviceConnection.getRawDescriptors(). It accepts
 * only a single well-bounded configuration and a master Volume Control on a Feature Unit directly
 * feeding a physical output terminal; other topologies fail closed.
 */
internal fun findAndroidUacPlaybackVolumeControl(descriptors: ByteArray): AndroidUacVolumeControl? {
    var offset = 0
    var controlInterface = -1
    var isAudioControlInterface = false
    val versionByInterface = mutableMapOf<Int, AndroidUacVersion>()
    val volumeUnits = mutableListOf<AndroidUacVolumeControl>()
    val physicalOutputSources = mutableMapOf<Int, MutableSet<Int>>()
    var configurationCount = 0
    var configurationEnd = -1
    var acTotalLength = 0
    var acBytesSeen = 0

    fun unsigned(index: Int): Int = descriptors[index].toInt() and 0xff
    fun littleEndian(index: Int, byteCount: Int): Int =
        (0 until byteCount).fold(0) { result, byte -> result or (unsigned(index + byte) shl (byte * 8)) }

    while (offset < descriptors.size) {
        if (descriptors.size - offset < 2) return null
        val length = unsigned(offset)
        val type = unsigned(offset + 1)
        if (length < 2 || offset + length > descriptors.size) return null

        if (type == USB_DESCRIPTOR_CONFIGURATION) {
            if (acTotalLength != 0 && acBytesSeen != acTotalLength) return null
            if (length < 9) return null
            val totalLength = littleEndian(offset + 2, 2)
            if (totalLength < length || offset + totalLength > descriptors.size) return null
            configurationCount += 1
            configurationEnd = offset + totalLength
            controlInterface = -1
            isAudioControlInterface = false
            acTotalLength = 0
            acBytesSeen = 0
        } else if (type == USB_DESCRIPTOR_INTERFACE) {
            if (acTotalLength != 0 && acBytesSeen != acTotalLength) return null
            if (length < 9) return null
            if (configurationEnd >= 0 && offset + length > configurationEnd) return null
            controlInterface = unsigned(offset + 2)
            isAudioControlInterface = unsigned(offset + 5) == USB_CLASS_AUDIO &&
                unsigned(offset + 6) == USB_SUBCLASS_AUDIO_CONTROL
            acTotalLength = 0
            acBytesSeen = 0
        } else if (isAudioControlInterface && type == USB_DESCRIPTOR_CS_INTERFACE && length >= 3) {
            if (configurationEnd >= 0 && offset + length > configurationEnd) return null
            if (acTotalLength != 0 && acBytesSeen + length > acTotalLength) return null
            if (acTotalLength != 0) acBytesSeen += length
            when (unsigned(offset + 2)) {
                USB_AC_HEADER -> {
                    if (length < 9 || controlInterface < 0 || acTotalLength != 0) return null
                    val bcdAdc = littleEndian(offset + 3, 2)
                    val version = when (bcdAdc ushr 8) {
                        1 -> AndroidUacVersion.Uac1
                        2 -> AndroidUacVersion.Uac2
                        else -> null
                    }
                    if (version != null) {
                        val validHeader = when (version) {
                            AndroidUacVersion.Uac1 -> length == 8 + unsigned(offset + 7)
                            AndroidUacVersion.Uac2 -> length == 9
                        }
                        if (!validHeader) return null
                        val totalLengthOffset = when (version) {
                            AndroidUacVersion.Uac1 -> offset + 5
                            AndroidUacVersion.Uac2 -> offset + 6
                        }
                        val totalLength = littleEndian(totalLengthOffset, 2)
                        if (totalLength < length ||
                            (configurationEnd >= 0 && offset + totalLength > configurationEnd)
                        ) return null
                        acTotalLength = totalLength
                        acBytesSeen = length
                        versionByInterface[controlInterface] = version
                    }
                }

                USB_AC_FEATURE_UNIT -> {
                    val version = versionByInterface[controlInterface] ?: return null
                    if (length < 7) return null
                    val unitId = unsigned(offset + 3)
                    val hasMasterVolumeControl = when (version) {
                        AndroidUacVersion.Uac1 -> {
                            val controlSize = unsigned(offset + 5)
                            if (controlSize !in 1..4 || length < 7 + controlSize ||
                                (length - 7) % controlSize != 0
                            ) return null
                            val controls = littleEndian(offset + 6, controlSize)
                            (controls and UAC1_VOLUME_CONTROL_BIT) != 0
                        }

                        AndroidUacVersion.Uac2 -> {
                            if (length < 14 || (length - 6) % 4 != 0) return null
                            val masterControls = littleEndian(offset + 5, 4)
                            ((masterControls ushr UAC2_VOLUME_CONTROL_SHIFT) and 0x3) ==
                                UAC2_HOST_PROGRAMMABLE
                        }
                    }
                    if (hasMasterVolumeControl) {
                        volumeUnits += AndroidUacVolumeControl(version, controlInterface, unitId)
                    }
                }

                USB_AC_OUTPUT_TERMINAL -> {
                    if (length < 9 || controlInterface < 0) return null
                    val terminalType = littleEndian(offset + 4, 2)
                    if (terminalType and USB_TERMINAL_TYPE_MASK == USB_TERMINAL_TYPE_PHYSICAL_OUTPUT) {
                        physicalOutputSources.getOrPut(controlInterface, ::mutableSetOf)
                            .add(unsigned(offset + 7))
                    }
                }
            }
        }
        if (configurationEnd >= 0 && offset + length > configurationEnd) return null
        offset += length
    }

    if (acTotalLength != 0 && acBytesSeen != acTotalLength) return null
    if (configurationCount != 1 || configurationEnd != descriptors.size) return null

    val matches = volumeUnits.filter { control ->
        control.unitId in physicalOutputSources[control.controlInterfaceNumber].orEmpty()
    }
    return matches.singleOrNull()
}

internal fun androidUacGetCurrentVolumeRequest(control: AndroidUacVolumeControl): AndroidUsbControlRequest =
    AndroidUsbControlRequest(
        requestType = USB_CLASS_INTERFACE_IN,
        request = if (control.version == AndroidUacVersion.Uac1) UAC1_GET_CUR else UAC2_CUR,
        value = (UAC_VOLUME_CONTROL_SELECTOR shl 8) or control.channelNumber,
        index = (control.unitId shl 8) or control.controlInterfaceNumber,
        data = ByteArray(UAC_VOLUME_VALUE_BYTES),
        length = UAC_VOLUME_VALUE_BYTES,
    )

internal fun androidUacGetVolumeRangeRequest(
    control: AndroidUacVolumeControl,
    length: Int,
): AndroidUsbControlRequest {
    require(length in 2..MAX_UAC_RANGE_RESPONSE_BYTES)
    return AndroidUsbControlRequest(
        requestType = USB_CLASS_INTERFACE_IN,
        request = if (control.version == AndroidUacVersion.Uac1) UAC1_GET_MIN else UAC2_RANGE,
        value = (UAC_VOLUME_CONTROL_SELECTOR shl 8) or control.channelNumber,
        index = (control.unitId shl 8) or control.controlInterfaceNumber,
        data = ByteArray(length),
        length = length,
    )
}

internal fun androidUacUac1VolumeRangeRequests(
    control: AndroidUacVolumeControl,
): List<AndroidUsbControlRequest> {
    require(control.version == AndroidUacVersion.Uac1)
    return listOf(UAC1_GET_MIN, UAC1_GET_MAX, UAC1_GET_RES).map { request ->
        AndroidUsbControlRequest(
            requestType = USB_CLASS_INTERFACE_IN,
            request = request,
            value = (UAC_VOLUME_CONTROL_SELECTOR shl 8) or control.channelNumber,
            index = (control.unitId shl 8) or control.controlInterfaceNumber,
            data = ByteArray(UAC_VOLUME_VALUE_BYTES),
            length = UAC_VOLUME_VALUE_BYTES,
        )
    }
}

internal fun parseAndroidUac2VolumeRanges(data: ByteArray, expectedSubRanges: Int): List<AndroidUacVolumeRange> {
    require(expectedSubRanges in 1..MAX_UAC_VOLUME_SUBRANGES)
    require(data.size == 2 + expectedSubRanges * 6) { "UAC2 RANGE response has an invalid length" }
    val count = (data[0].toInt() and 0xff) or ((data[1].toInt() and 0xff) shl 8)
    require(count == expectedSubRanges) { "UAC2 RANGE subrange count changed during the request" }
    val ranges = (0 until count).map { index ->
        val offset = 2 + index * 6
        AndroidUacVolumeRange(
            minimumDb256 = data.readUacSigned16(offset),
            maximumDb256 = data.readUacSigned16(offset + 2),
            resolutionDb256 = data.readUacSigned16(offset + 4),
        )
    }
    require(ranges.zipWithNext().all { (left, right) -> left.maximumDb256 < right.minimumDb256 }) {
        "UAC2 RANGE subranges must be ordered and non-overlapping"
    }
    return ranges
}

internal fun androidUacSetCurrentVolumeRequest(
    control: AndroidUacVolumeControl,
    volumeDb256: Int,
): AndroidUsbControlRequest {
    require(volumeDb256 in Short.MIN_VALUE..Short.MAX_VALUE) { "UAC volume is a signed 16-bit dB value" }
    return AndroidUsbControlRequest(
        requestType = USB_CLASS_INTERFACE_OUT,
        request = UAC_SET_CUR,
        value = (UAC_VOLUME_CONTROL_SELECTOR shl 8) or control.channelNumber,
        index = (control.unitId shl 8) or control.controlInterfaceNumber,
        data = byteArrayOf(volumeDb256.toByte(), (volumeDb256 ushr 8).toByte()),
        length = UAC_VOLUME_VALUE_BYTES,
    )
}

internal interface AndroidUsbControlTransfer {
    fun transfer(request: AndroidUsbControlRequest, timeoutMillis: Int = UAC_CONTROL_TIMEOUT_MILLIS): Int
}

internal class AndroidUsbConnectionControlTransfer(
    private val connection: UsbDeviceConnection,
) : AndroidUsbControlTransfer {
    override fun transfer(request: AndroidUsbControlRequest, timeoutMillis: Int): Int =
        connection.controlTransfer(
            request.requestType,
            request.request,
            request.value,
            request.index,
            request.data,
            0,
            request.length,
            timeoutMillis,
        )
}

/** SET_CUR is reported successful only when a GET_CUR readback returns the device's accepted value. */
internal class AndroidUacHardwareVolume(
    private val control: AndroidUacVolumeControl,
    private val transfer: AndroidUsbControlTransfer,
) {
    fun readVolumeRanges(): List<AndroidUacVolumeRange> = when (control.version) {
        AndroidUacVersion.Uac1 -> {
            val requests = androidUacUac1VolumeRangeRequests(control)
            val values = requests.map(::readSignedDb256)
            listOf(AndroidUacVolumeRange(values[0], values[1], values[2]))
        }

        AndroidUacVersion.Uac2 -> {
            val countRequest = androidUacGetVolumeRangeRequest(control, 2)
            check(transfer.transfer(countRequest) == 2) { "USB Audio Class GET_RANGE header failed" }
            val count = (countRequest.data[0].toInt() and 0xff) or
                ((countRequest.data[1].toInt() and 0xff) shl 8)
            require(count in 1..MAX_UAC_VOLUME_SUBRANGES) { "USB Audio Class GET_RANGE returned an invalid count" }
            val rangeRequest = androidUacGetVolumeRangeRequest(control, 2 + count * 6)
            check(transfer.transfer(rangeRequest) == rangeRequest.length) { "USB Audio Class GET_RANGE failed" }
            parseAndroidUac2VolumeRanges(rangeRequest.data, count)
        }
    }

    fun readCurrentVolume(): AndroidUacVolumeValue {
        val raw = readSignedDb256(androidUacGetCurrentVolumeRequest(control))
        return if (raw == Short.MIN_VALUE.toInt()) AndroidUacVolumeValue.Muted
        else AndroidUacVolumeValue.Finite(raw)
    }

    fun setAndReadBackDb256(
        volumeDb256: Int,
        ranges: List<AndroidUacVolumeRange> = readVolumeRanges(),
    ): AndroidUacVolumeValue {
        val request = androidUacSetCurrentVolumeRequest(control, volumeDb256)
        require(ranges.any { it.contains(volumeDb256) }) {
            "USB Audio Class volume is outside the advertised range or resolution"
        }
        val sent = transfer.transfer(request)
        check(sent == request.length) { "USB Audio Class SET_CUR failed ($sent)" }
        return readCurrentVolume()
    }

    private fun readSignedDb256(request: AndroidUsbControlRequest): Int {
        val received = transfer.transfer(request)
        check(received == request.length) { "USB Audio Class GET_CUR failed ($received)" }
        return request.data.readUacSigned16(0)
    }
}

private fun ByteArray.readUacSigned16(offset: Int): Int =
    ((this[offset + 1].toInt() and 0xff) shl 8 or (this[offset].toInt() and 0xff)).toShort().toInt()

private const val USB_DESCRIPTOR_CONFIGURATION = 0x02
private const val USB_DESCRIPTOR_INTERFACE = 0x04
private const val USB_DESCRIPTOR_CS_INTERFACE = 0x24
private const val USB_CLASS_AUDIO = 0x01
private const val USB_SUBCLASS_AUDIO_CONTROL = 0x01
private const val USB_AC_HEADER = 0x01
private const val USB_AC_OUTPUT_TERMINAL = 0x03
private const val USB_AC_FEATURE_UNIT = 0x06
private const val USB_TERMINAL_TYPE_MASK = 0xff00
private const val USB_TERMINAL_TYPE_PHYSICAL_OUTPUT = 0x0300
private const val UAC1_VOLUME_CONTROL_BIT = 0x02
private const val UAC2_VOLUME_CONTROL_SHIFT = 2
private const val UAC2_HOST_PROGRAMMABLE = 0x03
private const val UAC_VOLUME_CONTROL_SELECTOR = 0x02
private const val USB_CLASS_INTERFACE_OUT = 0x21
private const val USB_CLASS_INTERFACE_IN = 0xa1
private const val UAC1_GET_CUR = 0x81
private const val UAC2_CUR = 0x01
private const val UAC2_RANGE = 0x02
private const val UAC_SET_CUR = 0x01
private const val UAC1_GET_MIN = 0x82
private const val UAC1_GET_MAX = 0x83
private const val UAC1_GET_RES = 0x84
private const val UAC_VOLUME_VALUE_BYTES = 2
private const val UAC_CONTROL_TIMEOUT_MILLIS = 1_000
private const val MAX_UAC_VOLUME_SUBRANGES = 16
private const val MAX_UAC_RANGE_RESPONSE_BYTES = 2 + MAX_UAC_VOLUME_SUBRANGES * 6
