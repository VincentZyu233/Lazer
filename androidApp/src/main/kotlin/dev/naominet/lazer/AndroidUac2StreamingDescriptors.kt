package dev.naominet.lazer

/** A UAC2 AudioStreaming alternate setting with a PCM Type-I isochronous OUT endpoint. */
internal data class AndroidUac2PlaybackAltSetting(
    val configurationValue: Int,
    val controlInterfaceNumber: Int,
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val terminalLink: Int,
    val clockSourceId: Int,
    val channelCount: Int,
    val channelConfig: Long,
    val clockFrequencyAccess: AndroidUac2ClockFrequencyAccess,
    val subslotSizeBytes: Int,
    val validBitResolution: Int,
    val dataEndpoint: AndroidUac2IsochronousEndpoint,
    val feedbackEndpoint: AndroidUac2IsochronousEndpoint?,
    val clockSelector: AndroidUac2ClockSelector? = null,
    val clockSourceCandidates: List<AndroidUac2ClockSelectorCandidate> = emptyList(),
)

/** The endpoint packet size excludes the high-speed transaction multiplier encoded in wMaxPacketSize. */
internal data class AndroidUac2IsochronousEndpoint(
    val address: Int,
    val synchronizationType: Int,
    val usageType: Int,
    val maximumPacketSizeBytes: Int,
    val transactionsPerMicroframe: Int,
    val interval: Int,
)

/** A Clock Source entity that is directly linked from the AudioStreaming terminal. */
internal data class AndroidUac2ClockSource(
    val controlInterfaceNumber: Int,
    val clockSourceId: Int,
    val frequencyAccess: AndroidUac2ClockFrequencyAccess,
)

/** One input pin on a directly linked UAC2 Clock Selector, numbered from one per the spec. */
internal data class AndroidUac2ClockSelectorCandidate(
    val pin: Int,
    val clockSource: AndroidUac2ClockSource,
)

internal data class AndroidUac2ClockSelector(
    val controlInterfaceNumber: Int,
    val clockSelectorId: Int,
    val selectionAccess: AndroidUac2ClockFrequencyAccess,
)

internal data class AndroidUac2ClockSelection(
    val pin: Int,
    val clockSource: AndroidUac2ClockSource,
)

internal enum class AndroidUac2ClockFrequencyAccess {
    ReadOnly,
    HostProgrammable,
}

internal data class AndroidUac2ClockFrequencyRange(
    val minimumHz: Long,
    val maximumHz: Long,
    val resolutionHz: Long,
) {
    init {
        require(minimumHz in 0L..UAC2_UINT32_MAX_HZ) { "Clock range minimum is not an unsigned 32-bit value" }
        require(maximumHz in minimumHz..UAC2_UINT32_MAX_HZ) {
            "Clock range minimum must not exceed its maximum"
        }
        require(resolutionHz in 0L..UAC2_UINT32_MAX_HZ) {
            "Clock range resolution is not an unsigned 32-bit value"
        }
        if (minimumHz == maximumHz) {
            require(resolutionHz == 0L) { "Single-value clock ranges must advertise zero resolution" }
        } else {
            require(resolutionHz > 0L && (maximumHz - minimumHz) % resolutionHz == 0L) {
                "Clock range maximum must lie on the advertised resolution grid"
            }
        }
    }

    fun contains(sampleRateHz: Long): Boolean =
        sampleRateHz in minimumHz..maximumHz &&
            (resolutionHz == 0L || (sampleRateHz - minimumHz) % resolutionHz == 0L)
}

/** A resolved alternate setting and the exact source sample rate selected from its Clock Source. */
internal data class AndroidUac2PlaybackStreamPlan(
    val configurationValue: Int,
    val controlInterfaceNumber: Int,
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val clockSourceId: Int,
    val clockFrequencyAccess: AndroidUac2ClockFrequencyAccess,
    val sampleRateHz: Long,
    val channelCount: Int,
    val channelConfig: Long,
    val subslotSizeBytes: Int,
    val validBitResolution: Int,
    val dataEndpoint: AndroidUac2IsochronousEndpoint,
    val feedbackEndpoint: AndroidUac2IsochronousEndpoint?,
    val clockSelector: AndroidUac2ClockSelector? = null,
    val clockSourceCandidates: List<AndroidUac2ClockSelectorCandidate> = emptyList(),
)

/** Resolves a stream only when the requested rate is explicitly advertised by the device clock. */
internal fun planAndroidUac2PcmPlayback(
    alternate: AndroidUac2PlaybackAltSetting,
    sampleRateHz: Long,
    supportedRanges: List<AndroidUac2ClockFrequencyRange>,
): AndroidUac2PlaybackStreamPlan? {
    if (alternate.clockSelector != null) return null // Requires the session's multi-source selector planner.
    if (sampleRateHz <= 0 || supportedRanges.none { it.contains(sampleRateHz) }) return null
    if (alternate.dataEndpoint.synchronizationType == USB_ISO_SYNC_ASYNCHRONOUS &&
        alternate.feedbackEndpoint == null
    ) return null // Implicit feedback discovery is not implemented yet.
    return AndroidUac2PlaybackStreamPlan(
        configurationValue = alternate.configurationValue,
        controlInterfaceNumber = alternate.controlInterfaceNumber,
        interfaceNumber = alternate.interfaceNumber,
        alternateSetting = alternate.alternateSetting,
        clockSourceId = alternate.clockSourceId,
        clockFrequencyAccess = alternate.clockFrequencyAccess,
        sampleRateHz = sampleRateHz,
        channelCount = alternate.channelCount,
        channelConfig = alternate.channelConfig,
        subslotSizeBytes = alternate.subslotSizeBytes,
        validBitResolution = alternate.validBitResolution,
        dataEndpoint = alternate.dataEndpoint,
        feedbackEndpoint = alternate.feedbackEndpoint,
        clockSelector = alternate.clockSelector,
        clockSourceCandidates = alternate.clockSourceCandidates,
    )
}

/** Picks the current selector input when possible; switching requires a writable selector. */
internal fun planAndroidUac2ClockSelectorSelection(
    selector: AndroidUac2ClockSelector,
    candidates: List<AndroidUac2ClockSelectorCandidate>,
    currentPin: Int,
    supportedRangesByPin: Map<Int, List<AndroidUac2ClockFrequencyRange>>,
    sampleRateHz: Long,
): AndroidUac2ClockSelection? {
    if (sampleRateHz <= 0 || candidates.isEmpty() ||
        candidates.map { it.pin } != (1..candidates.size).toList()
    ) {
        return null
    }
    val candidateByPin = candidates.associateBy(AndroidUac2ClockSelectorCandidate::pin)
    val current = candidateByPin[currentPin] ?: return null
    if (supportedRangesByPin[currentPin].orEmpty().any { it.contains(sampleRateHz) }) {
        return AndroidUac2ClockSelection(current.pin, current.clockSource)
    }
    if (selector.selectionAccess != AndroidUac2ClockFrequencyAccess.HostProgrammable) return null
    return candidates.firstOrNull { candidate ->
        candidate.pin != currentPin &&
            supportedRangesByPin[candidate.pin].orEmpty().any { it.contains(sampleRateHz) }
    }?.let { AndroidUac2ClockSelection(it.pin, it.clockSource) }
}

/** Builds Clock Selector Control GET_CUR (one-based input pin number). */
internal fun androidUac2ClockSelectorGetCurrentPinRequest(
    selector: AndroidUac2ClockSelector,
): AndroidUsbControlRequest = androidUac2ClockSelectorRequest(selector, USB_CLASS_INTERFACE_IN, UAC2_GET_CUR)

/** Builds SET_CUR only when the selector advertises a host-programmable Selection Control. */
internal fun androidUac2ClockSelectorSetCurrentPinRequest(
    selector: AndroidUac2ClockSelector,
    pin: Int,
    candidateCount: Int,
): AndroidUsbControlRequest {
    require(selector.selectionAccess == AndroidUac2ClockFrequencyAccess.HostProgrammable) {
        "Clock Selector Selection Control is read-only"
    }
    require(pin in 1..candidateCount && candidateCount <= 0xff) { "Clock Selector input pin is out of range" }
    return androidUac2ClockSelectorRequest(selector, USB_CLASS_INTERFACE_OUT, UAC2_SET_CUR).also {
        it.data[0] = pin.toByte()
    }
}

internal fun parseAndroidUac2ClockSelectorCurrentPin(response: ByteArray, candidateCount: Int): Int {
    require(response.size == 1) { "Clock Selector GET_CUR response must be one byte" }
    val pin = response[0].toInt() and 0xff
    require(pin in 1..candidateCount) { "Clock Selector GET_CUR returned an invalid pin" }
    return pin
}

private fun androidUac2ClockSelectorRequest(
    selector: AndroidUac2ClockSelector,
    requestType: Int,
    request: Int,
): AndroidUsbControlRequest {
    require(selector.controlInterfaceNumber in 0..0xff) { "AudioControl interface number is out of range" }
    require(selector.clockSelectorId in 1..0xff) { "Clock Selector ID must be nonzero" }
    return AndroidUsbControlRequest(
        requestType = requestType,
        request = request,
        value = UAC2_CLOCK_SELECTOR_CONTROL_SELECTOR shl 8,
        index = (selector.clockSelectorId shl 8) or selector.controlInterfaceNumber,
        data = ByteArray(1),
        length = 1,
    )
}

/**
 * Parses bounded raw USB descriptors returned by `UsbDeviceConnection.getRawDescriptors()`.
 * Android can return a Device Descriptor followed by multiple configurations; each configuration
 * is parsed independently and the configuration value is retained for later interface claims.
 * Malformed or ambiguous descriptors return null. One direct Clock Selector → Clock Source hop
 * is supported; Clock Multipliers and nested selectors fail closed.
 */
internal fun parseAndroidUac2PlaybackAltSettings(
    descriptors: ByteArray,
): List<AndroidUac2PlaybackAltSetting>? {
    if (descriptors.size !in USB_CONFIGURATION_DESCRIPTOR_BYTES..MAX_USB_RAW_DESCRIPTOR_BYTES) return null
    var offset = 0
    var descriptorCount = 0
    var expectedConfigurationCount: Int? = null
    val configurationValues = mutableSetOf<Int>()
    val output = mutableListOf<AndroidUac2PlaybackAltSetting>()

    fun unsigned(index: Int): Int = descriptors[index].toInt() and 0xff
    fun littleEndian(index: Int, byteCount: Int): Int =
        (0 until byteCount).fold(0) { result, byte -> result or (unsigned(index + byte) shl (byte * 8)) }

    while (offset < descriptors.size) {
        if (++descriptorCount > MAX_USB_CONFIGURATIONS + 1 || descriptors.size - offset < 2) return null
        val length = unsigned(offset)
        val type = unsigned(offset + 1)
        if (length < 2 || offset + length > descriptors.size) return null
        when (type) {
            USB_DESCRIPTOR_DEVICE -> {
                if (offset != 0 || expectedConfigurationCount != null ||
                    length != USB_DEVICE_DESCRIPTOR_BYTES
                ) return null
                expectedConfigurationCount = unsigned(offset + 17).takeIf { it > 0 } ?: return null
                offset += length
            }

            USB_DESCRIPTOR_CONFIGURATION -> {
                if (length != USB_CONFIGURATION_DESCRIPTOR_BYTES) return null
                val configurationLength = littleEndian(offset + 2, 2)
                val configurationValue = unsigned(offset + 5)
                if (configurationLength !in USB_CONFIGURATION_DESCRIPTOR_BYTES..MAX_USB_CONFIGURATION_BYTES ||
                    offset + configurationLength > descriptors.size || configurationValue == 0 ||
                    !configurationValues.add(configurationValue)
                ) return null
                if (expectedConfigurationCount != null && configurationValues.size > expectedConfigurationCount) return null
                val configuration = descriptors.copyOfRange(offset, offset + configurationLength)
                val parsed = parseAndroidUac2PlaybackConfiguration(configuration) ?: return null
                output += parsed
                offset += configurationLength
            }

            else -> return null
        }
    }

    if (configurationValues.isEmpty() ||
        (expectedConfigurationCount != null && configurationValues.size != expectedConfigurationCount)
    ) return null
    return output.sortedWith(
        compareBy(
            AndroidUac2PlaybackAltSetting::configurationValue,
            AndroidUac2PlaybackAltSetting::interfaceNumber,
            AndroidUac2PlaybackAltSetting::alternateSetting,
        ),
    )
}

private fun parseAndroidUac2PlaybackConfiguration(
    descriptors: ByteArray,
): List<AndroidUac2PlaybackAltSetting>? {
    if (descriptors.size !in USB_CONFIGURATION_DESCRIPTOR_BYTES..MAX_USB_CONFIGURATION_BYTES) return null

    val interfaces = linkedMapOf<Pair<Int, Int>, UsbInterfaceBuilder>()
    val audioAssociations = mutableListOf<AudioFunctionAssociation>()
    var configurationCount = 0
    var configurationOffset = -1
    var configurationLength = 0
    var configurationValue = 0
    var configurationInterfaceCount = 0
    var currentInterface: UsbInterfaceBuilder? = null
    var offset = 0
    var descriptorCount = 0

    fun unsigned(index: Int): Int = descriptors[index].toInt() and 0xff
    fun littleEndian(index: Int, byteCount: Int): Long =
        (0 until byteCount).fold(0L) { result, byte ->
            result or (unsigned(index + byte).toLong() shl (byte * 8))
        }

    while (offset < descriptors.size) {
        if (++descriptorCount > MAX_USB_DESCRIPTORS || descriptors.size - offset < 2) return null
        val length = unsigned(offset)
        val type = unsigned(offset + 1)
        if (length < 2 || offset + length > descriptors.size) return null

        when (type) {
            USB_DESCRIPTOR_DEVICE -> {
                return null
            }

            USB_DESCRIPTOR_CONFIGURATION -> {
                if (configurationCount != 0 || offset != 0 || length != USB_CONFIGURATION_DESCRIPTOR_BYTES) return null
                configurationCount = 1
                configurationOffset = offset
                configurationLength = littleEndian(offset + 2, 2).toInt()
                configurationValue = unsigned(offset + 5)
                configurationInterfaceCount = unsigned(offset + 4)
                if (configurationValue == 0 || configurationLength != descriptors.size - configurationOffset ||
                    configurationInterfaceCount == 0
                ) return null
                currentInterface = null
            }

            USB_DESCRIPTOR_INTERFACE_ASSOCIATION -> {
                if (configurationCount != 1 || length != USB_INTERFACE_ASSOCIATION_DESCRIPTOR_BYTES) return null
                val firstInterface = unsigned(offset + 2)
                val interfaceCount = unsigned(offset + 3)
                val functionClass = unsigned(offset + 4)
                val functionSubclass = unsigned(offset + 5)
                val functionProtocol = unsigned(offset + 6)
                if (interfaceCount == 0 || firstInterface + interfaceCount > MAX_USB_INTERFACE_NUMBER_EXCLUSIVE) return null
                if (functionClass == USB_CLASS_AUDIO &&
                    functionSubclass == USB_FUNCTION_SUBCLASS_UNDEFINED
                ) {
                    audioAssociations += AudioFunctionAssociation(
                        firstInterface = firstInterface,
                        interfaceCount = interfaceCount,
                        protocol = functionProtocol,
                    )
                }
                currentInterface = null
            }

            USB_DESCRIPTOR_INTERFACE -> {
                if (configurationCount != 1 || length != USB_INTERFACE_DESCRIPTOR_BYTES) return null
                val builder = UsbInterfaceBuilder(
                    number = unsigned(offset + 2),
                    alternateSetting = unsigned(offset + 3),
                    declaredEndpointCount = unsigned(offset + 4),
                    interfaceClass = unsigned(offset + 5),
                    interfaceSubclass = unsigned(offset + 6),
                    interfaceProtocol = unsigned(offset + 7),
                )
                val key = builder.number to builder.alternateSetting
                if (interfaces.putIfAbsent(key, builder) != null) return null
                currentInterface = builder
            }

            USB_DESCRIPTOR_ENDPOINT -> {
                if (configurationCount != 1 || length != USB_ENDPOINT_DESCRIPTOR_BYTES) return null
                val owner = currentInterface ?: return null
                val endpoint = parseEndpoint(
                    address = unsigned(offset + 2),
                    attributes = unsigned(offset + 3),
                    rawMaximumPacketSize = littleEndian(offset + 4, 2).toInt(),
                    interval = unsigned(offset + 6),
                ) ?: return null
                if (owner.endpoints.any { it.address == endpoint.address }) return null
                owner.endpoints += endpoint
            }

            USB_DESCRIPTOR_CS_INTERFACE -> {
                val owner = currentInterface
                if (configurationCount != 1 || owner == null) return null
                if (owner.interfaceClass == USB_CLASS_AUDIO &&
                    (owner.interfaceSubclass == USB_SUBCLASS_AUDIO_CONTROL ||
                        owner.interfaceSubclass == USB_SUBCLASS_AUDIO_STREAMING)
                ) {
                    owner.classSpecificInterfaces += ClassSpecificInterfaceDescriptor(
                        offset = offset,
                        length = length,
                        subtype = unsigned(offset + 2).takeIf { length >= 3 } ?: return null,
                        bytes = descriptors.copyOfRange(offset, offset + length),
                    )
                }
            }
        }
        offset += length
    }

    if (configurationCount != 1 || configurationLength != descriptors.size - configurationOffset) return null
    if (interfaces.values.map(UsbInterfaceBuilder::number).toSet().size != configurationInterfaceCount) return null
    if (audioAssociations.indices.any { leftIndex ->
            (leftIndex + 1 until audioAssociations.size).any { rightIndex ->
                val left = audioAssociations[leftIndex]
                val right = audioAssociations[rightIndex]
                left.firstInterface < right.firstInterface + right.interfaceCount &&
                    right.firstInterface < left.firstInterface + left.interfaceCount
            }
        }
    ) return null
    if (interfaces.values.any { it.endpoints.size != it.declaredEndpointCount }) return null

    val controlFunctions = mutableMapOf<Int, ParsedUac2ControlFunction>()
    for (controlInterface in interfaces.values.filter {
            it.alternateSetting == 0 && it.interfaceClass == USB_CLASS_AUDIO &&
                it.interfaceSubclass == USB_SUBCLASS_AUDIO_CONTROL &&
                it.interfaceProtocol == USB_PROTOCOL_UAC2
        }
    ) {
        val parsed = parseControlFunction(controlInterface) ?: return null
        controlFunctions[controlInterface.number] = parsed
    }

    val streamingInterfaces = interfaces.values.filter {
        it.interfaceClass == USB_CLASS_AUDIO && it.interfaceSubclass == USB_SUBCLASS_AUDIO_STREAMING &&
            it.interfaceProtocol == USB_PROTOCOL_UAC2
    }
    if (streamingInterfaces.isEmpty()) return emptyList()

    val output = mutableListOf<AndroidUac2PlaybackAltSetting>()
    for (streamingInterface in streamingInterfaces.filter { it.alternateSetting != 0 }) {
        // Ignore valid capture-only AudioStreaming alternates; they have an IN data endpoint and
        // are outside this playback parser's scope.
        if (streamingInterface.endpoints.none {
                it.isIsochronous && it.direction == UsbEndpointDirection.Out && it.usageType == USB_ISO_USAGE_DATA
            }
        ) continue

        val generalDescriptors = streamingInterface.classSpecificInterfaces.filter { it.subtype == UAC_AS_GENERAL }
        if (generalDescriptors.size != 1 || generalDescriptors.single().length != UAC2_AS_GENERAL_DESCRIPTOR_BYTES) {
            return null
        }
        val general = generalDescriptors.single().bytes
        val formatType = general.u8(5)
        val formatMask = readUnsignedLittleEndian(general, 6, 4)
        if (formatType != UAC_FORMAT_TYPE_I || (formatMask and UAC2_PCM_FORMAT_BIT) == 0L) continue

        val controlInterfaceNumber = associatedControlInterface(
            streamingInterfaceNumber = streamingInterface.number,
            controlFunctions = controlFunctions,
            associations = audioAssociations,
        ) ?: return null
        val controlFunction = controlFunctions[controlInterfaceNumber] ?: return null
        val parsedAlt = parsePlaybackAltSetting(streamingInterface, controlFunction, configurationValue) ?: return null
        output += parsedAlt
    }

    if (output.map { it.interfaceNumber to it.alternateSetting }.toSet().size != output.size) return null
    return output.sortedWith(compareBy(AndroidUac2PlaybackAltSetting::interfaceNumber, AndroidUac2PlaybackAltSetting::alternateSetting))
}

/** Builds the two-byte UAC2 GET_RANGE length probe for Clock Source Sampling Frequency. */
internal fun androidUac2ClockSourceGetRangeHeaderRequest(
    source: AndroidUac2ClockSource,
): AndroidUsbControlRequest = androidUac2ClockSourceGetRangeRequest(source, subRangeCount = null)

/** Builds the full UAC2 GET_RANGE request after the header probe reports its subrange count. */
internal fun androidUac2ClockSourceGetRangeRequest(
    source: AndroidUac2ClockSource,
    subRangeCount: Int,
): AndroidUsbControlRequest = androidUac2ClockSourceGetRangeRequest(source, subRangeCount as Int?)

/** Builds the four-byte GET_CUR request for the Clock Source Sampling Frequency Control. */
internal fun androidUac2ClockSourceGetCurrentFrequencyRequest(
    source: AndroidUac2ClockSource,
): AndroidUsbControlRequest {
    require(source.controlInterfaceNumber in 0..0xff) { "AudioControl interface number is out of range" }
    require(source.clockSourceId in 1..0xff) { "Clock Source ID must be nonzero" }
    return AndroidUsbControlRequest(
        requestType = USB_CLASS_INTERFACE_IN,
        request = UAC2_GET_CUR,
        value = UAC2_CLOCK_FREQUENCY_CONTROL_SELECTOR shl 8,
        index = (source.clockSourceId shl 8) or source.controlInterfaceNumber,
        data = ByteArray(UAC2_CLOCK_FREQUENCY_BYTES),
        length = UAC2_CLOCK_FREQUENCY_BYTES,
    )
}

/** Parses a four-byte little-endian UAC2 Sampling Frequency GET_CUR response. */
internal fun parseAndroidUac2ClockSourceCurrentFrequencyHz(response: ByteArray): Long {
    require(response.size == UAC2_CLOCK_FREQUENCY_BYTES) { "Clock Source GET_CUR response must be four bytes" }
    return readUnsignedLittleEndian(response, 0, UAC2_CLOCK_FREQUENCY_BYTES)
}

/** Builds SET_CUR only for host-programmable clocks and rates advertised by GET_RANGE. */
internal fun androidUac2ClockSourceSetCurrentFrequencyRequest(
    source: AndroidUac2ClockSource,
    sampleRateHz: Long,
    supportedRanges: List<AndroidUac2ClockFrequencyRange>,
): AndroidUsbControlRequest {
    require(source.frequencyAccess == AndroidUac2ClockFrequencyAccess.HostProgrammable) {
        "Clock Source Sampling Frequency is read-only"
    }
    require(source.controlInterfaceNumber in 0..0xff) { "AudioControl interface number is out of range" }
    require(source.clockSourceId in 1..0xff) { "Clock Source ID must be nonzero" }
    require(sampleRateHz in 1L..UAC2_UINT32_MAX_HZ && supportedRanges.any { it.contains(sampleRateHz) }) {
        "Requested sample rate is not advertised by the Clock Source"
    }
    val data = ByteArray(UAC2_CLOCK_FREQUENCY_BYTES) { index ->
        (sampleRateHz ushr (index * 8)).toByte()
    }
    return AndroidUsbControlRequest(
        requestType = USB_CLASS_INTERFACE_OUT,
        request = UAC2_SET_CUR,
        value = UAC2_CLOCK_FREQUENCY_CONTROL_SELECTOR shl 8,
        index = (source.clockSourceId shl 8) or source.controlInterfaceNumber,
        data = data,
        length = data.size,
    )
}

private fun androidUac2ClockSourceGetRangeRequest(
    source: AndroidUac2ClockSource,
    subRangeCount: Int?,
): AndroidUsbControlRequest {
    require(source.controlInterfaceNumber in 0..0xff) { "AudioControl interface number is out of range" }
    require(source.clockSourceId in 1..0xff) { "Clock Source ID must be nonzero" }
    if (subRangeCount != null) require(subRangeCount in 1..MAX_UAC2_CLOCK_SUBRANGES) {
        "Clock Source RANGE subrange count is out of bounds"
    }
    val length = if (subRangeCount == null) 2 else UAC2_RANGE_HEADER_BYTES + subRangeCount * UAC2_CLOCK_RANGE_BYTES
    return AndroidUsbControlRequest(
        requestType = USB_CLASS_INTERFACE_IN,
        request = UAC2_GET_RANGE,
        value = (UAC2_CLOCK_FREQUENCY_CONTROL_SELECTOR shl 8),
        index = (source.clockSourceId shl 8) or source.controlInterfaceNumber,
        data = ByteArray(length),
        length = length,
    )
}

/** Parses the unsigned 16-bit subrange count returned by the first GET_RANGE transfer. */
internal fun parseAndroidUac2ClockSourceGetRangeSubRangeCount(header: ByteArray): Int {
    require(header.size == UAC2_RANGE_HEADER_BYTES) { "Clock Source RANGE header must be two bytes" }
    val count = readUnsignedLittleEndian(header, 0, 2).toInt()
    require(count in 1..MAX_UAC2_CLOCK_SUBRANGES) { "Clock Source RANGE returned an invalid subrange count" }
    return count
}

/** Parses the complete UAC2 Clock Source Sampling Frequency GET_RANGE response. */
internal fun parseAndroidUac2ClockSourceFrequencyRanges(
    data: ByteArray,
    expectedSubRangeCount: Int? = null,
): List<AndroidUac2ClockFrequencyRange> {
    require(data.size >= UAC2_RANGE_HEADER_BYTES) { "Clock Source RANGE response is truncated" }
    val count = parseAndroidUac2ClockSourceGetRangeSubRangeCount(data.copyOfRange(0, UAC2_RANGE_HEADER_BYTES))
    if (expectedSubRangeCount != null) require(count == expectedSubRangeCount) {
        "Clock Source RANGE subrange count changed during the request"
    }
    require(data.size == UAC2_RANGE_HEADER_BYTES + count * UAC2_CLOCK_RANGE_BYTES) {
        "Clock Source RANGE response has an invalid length"
    }
    val ranges = (0 until count).map { index ->
        val offset = UAC2_RANGE_HEADER_BYTES + index * UAC2_CLOCK_RANGE_BYTES
        AndroidUac2ClockFrequencyRange(
            minimumHz = readUnsignedLittleEndian(data, offset, 4),
            maximumHz = readUnsignedLittleEndian(data, offset + 4, 4),
            resolutionHz = readUnsignedLittleEndian(data, offset + 8, 4),
        )
    }
    require(ranges.zipWithNext().all { (left, right) -> left.maximumHz < right.minimumHz }) {
        "Clock Source RANGE subranges must be ordered and non-overlapping"
    }
    return ranges
}

private fun parseControlFunction(interfaceDescriptor: UsbInterfaceBuilder): ParsedUac2ControlFunction? {
    if (interfaceDescriptor.endpoints.size > 1 || interfaceDescriptor.endpoints.any {
            it.direction != UsbEndpointDirection.In || it.transferType != USB_TRANSFER_TYPE_INTERRUPT
        }
    ) return null
    val headers = interfaceDescriptor.classSpecificInterfaces.filter { it.subtype == UAC_AC_HEADER }
    if (headers.size != 1 || headers.single().length != UAC2_AC_HEADER_DESCRIPTOR_BYTES) return null
    val header = headers.single().bytes
    if ((readUnsignedLittleEndian(header, 3, 2).toInt() ushr 8) != UAC_VERSION_MAJOR_2) return null
    val declaredTotalLength = readUnsignedLittleEndian(header, 6, 2).toInt()
    if (declaredTotalLength != interfaceDescriptor.classSpecificInterfaces.sumOf(ClassSpecificInterfaceDescriptor::length)) {
        return null
    }

    val entitySubtypes = mutableMapOf<Int, Int>()
    val inputTerminals = mutableMapOf<Int, Uac2InputTerminal>()
    val clockSources = mutableMapOf<Int, AndroidUac2ClockSource>()
    val clockSelectors = mutableMapOf<Int, ParsedUac2ClockSelector>()
    for (descriptor in interfaceDescriptor.classSpecificInterfaces) {
        if (descriptor.subtype == UAC_AC_HEADER) continue
        val bytes = descriptor.bytes
        when (descriptor.subtype) {
            UAC_AC_INPUT_TERMINAL -> {
                if (descriptor.length != UAC2_INPUT_TERMINAL_DESCRIPTOR_BYTES) return null
                val id = bytes.u8(3)
                val terminalType = bytes.u16(4)
                val clockId = bytes.u8(7)
                val channelCount = bytes.u8(8)
                if (id == 0 || clockId == 0 || channelCount == 0 ||
                    entitySubtypes.putIfAbsent(id, descriptor.subtype) != null
                ) return null
                inputTerminals[id] = Uac2InputTerminal(terminalType, clockId, channelCount)
            }

            UAC_AC_OUTPUT_TERMINAL -> {
                if (descriptor.length != UAC2_OUTPUT_TERMINAL_DESCRIPTOR_BYTES) return null
                val id = bytes.u8(3)
                if (id == 0 || entitySubtypes.putIfAbsent(id, descriptor.subtype) != null) return null
            }

            UAC_AC_CLOCK_SOURCE -> {
                if (descriptor.length != UAC2_CLOCK_SOURCE_DESCRIPTOR_BYTES) return null
                val id = bytes.u8(3)
                val frequencyControl = bytes.u8(5) and UAC2_CLOCK_FREQUENCY_CONTROL_MASK
                val frequencyAccess = when (frequencyControl) {
                    UAC2_CONTROL_READ_ONLY -> AndroidUac2ClockFrequencyAccess.ReadOnly
                    UAC2_CONTROL_READ_WRITE -> AndroidUac2ClockFrequencyAccess.HostProgrammable
                    else -> null // Absent and reserved controls cannot provide the required GET_RANGE.
                }
                if (id == 0 || frequencyAccess == null ||
                    entitySubtypes.putIfAbsent(id, descriptor.subtype) != null
                ) return null
                clockSources[id] = AndroidUac2ClockSource(interfaceDescriptor.number, id, frequencyAccess)
            }

            UAC_AC_CLOCK_SELECTOR -> {
                if (descriptor.length < UAC2_CLOCK_SELECTOR_MIN_BYTES) return null
                val id = bytes.u8(3)
                val inputClockCount = bytes.u8(4)
                if (id == 0 || inputClockCount == 0 ||
                    descriptor.length != UAC2_CLOCK_SELECTOR_FIXED_BYTES + inputClockCount ||
                    entitySubtypes.putIfAbsent(id, descriptor.subtype) != null
                ) return null
                if ((0 until inputClockCount).any { bytes.u8(5 + it) == 0 }) return null
                val rawSelectionControl = bytes.u8(5 + inputClockCount)
                if (rawSelectionControl and UAC2_CLOCK_SELECTOR_RESERVED_CONTROL_MASK != 0) return null
                val selectionControl = rawSelectionControl and UAC2_CLOCK_SELECTOR_SELECTION_CONTROL_MASK
                val selectionAccess = when (selectionControl) {
                    UAC2_CONTROL_READ_ONLY -> AndroidUac2ClockFrequencyAccess.ReadOnly
                    UAC2_CONTROL_READ_WRITE -> AndroidUac2ClockFrequencyAccess.HostProgrammable
                    else -> null // Selection must be readable so the current pin can be verified.
                }
                if (selectionAccess == null) return null
                clockSelectors[id] = ParsedUac2ClockSelector(
                    clockSelectorId = id,
                    inputClockSourceIds = (0 until inputClockCount).map { bytes.u8(5 + it) },
                    selectionAccess = selectionAccess,
                )
            }

            UAC_AC_CLOCK_MULTIPLIER -> {
                if (descriptor.length != UAC2_CLOCK_MULTIPLIER_DESCRIPTOR_BYTES) return null
                val id = bytes.u8(3)
                if (id == 0 || entitySubtypes.putIfAbsent(id, descriptor.subtype) != null) return null
            }

            else -> {
                // UAC2 entities have an ID in byte 3. Account for known entity descriptor subtypes
                // even when this slice does not need to interpret their other fields.
                if (descriptor.subtype in UAC_AC_ENTITY_SUBTYPE_RANGE) {
                    if (descriptor.length < 4) return null
                    val id = bytes.u8(3)
                    if (id == 0 || entitySubtypes.putIfAbsent(id, descriptor.subtype) != null) return null
                }
            }
        }
    }
    if (inputTerminals.values.any { terminal -> terminal.clockSourceId !in entitySubtypes }) return null
    if (clockSelectors.values.any { selector -> selector.inputClockSourceIds.any { it !in entitySubtypes } }) return null
    return ParsedUac2ControlFunction(interfaceDescriptor.number, inputTerminals, clockSources, clockSelectors, entitySubtypes)
}

private fun associatedControlInterface(
    streamingInterfaceNumber: Int,
    controlFunctions: Map<Int, ParsedUac2ControlFunction>,
    associations: List<AudioFunctionAssociation>,
): Int? {
    val matchingAssociations = associations.filter {
        streamingInterfaceNumber in it.firstInterface until it.firstInterface + it.interfaceCount
    }
    if (matchingAssociations.size > 1) return null
    val association = matchingAssociations.singleOrNull()
    if (association != null) {
        if (association.protocol != USB_PROTOCOL_UAC2) return null
        val controlInterfaces = controlFunctions.keys.filter {
            it in association.firstInterface until association.firstInterface + association.interfaceCount
        }
        return controlInterfaces.singleOrNull()
    }
    return controlFunctions.keys.singleOrNull()
}

/** Returns null for malformed/ambiguous PCM alt settings, or null value when this alt is not PCM OUT. */
private fun parsePlaybackAltSetting(
    interfaceDescriptor: UsbInterfaceBuilder,
    controlFunction: ParsedUac2ControlFunction,
    configurationValue: Int,
): AndroidUac2PlaybackAltSetting? {
    val general = interfaceDescriptor.classSpecificInterfaces.single { it.subtype == UAC_AS_GENERAL }.bytes
    val formatType = general.u8(5)
    val formatMask = readUnsignedLittleEndian(general, 6, 4)
    if (formatType != UAC_FORMAT_TYPE_I || (formatMask and UAC2_PCM_FORMAT_BIT) == 0L) return null

    val formatDescriptors = interfaceDescriptor.classSpecificInterfaces.filter { it.subtype == UAC_AS_FORMAT_TYPE }
    if (formatDescriptors.size != 1 || formatDescriptors.single().length != UAC2_TYPE_I_FORMAT_DESCRIPTOR_BYTES) return null
    val format = formatDescriptors.single().bytes
    if (format.u8(3) != UAC_FORMAT_TYPE_I) return null
    val subslotSizeBytes = format.u8(4)
    val validBitResolution = format.u8(5)
    if (subslotSizeBytes !in 2..4 || validBitResolution !in setOf(16, 24, 32) ||
        validBitResolution > subslotSizeBytes * 8
    ) return null

    val terminalLink = general.u8(3)
    val channelCount = general.u8(10)
    val channelConfig = readUnsignedLittleEndian(general, 11, 4)
    if (terminalLink == 0 || channelCount !in 1..MAX_UAC2_PCM_CHANNELS) return null
    val inputTerminal = controlFunction.inputTerminals[terminalLink] ?: return null
    if (inputTerminal.terminalType != USB_TERMINAL_TYPE_USB_STREAMING || inputTerminal.channelCount != channelCount) {
        return null
    }
    val clockEntitySubtype = controlFunction.entitySubtypes[inputTerminal.clockSourceId] ?: return null
    val clockSelector: AndroidUac2ClockSelector?
    val clockSource: AndroidUac2ClockSource
    val clockSourceCandidates: List<AndroidUac2ClockSelectorCandidate>
    when (clockEntitySubtype) {
        UAC_AC_CLOCK_SOURCE -> {
            clockSource = controlFunction.clockSources[inputTerminal.clockSourceId] ?: return null
            clockSelector = null
            clockSourceCandidates = emptyList()
        }

        UAC_AC_CLOCK_SELECTOR -> {
            val parsedSelector = controlFunction.clockSelectors[inputTerminal.clockSourceId] ?: return null
            val sources = parsedSelector.inputClockSourceIds.mapIndexed { index, sourceId ->
                if (controlFunction.entitySubtypes[sourceId] != UAC_AC_CLOCK_SOURCE) return null
                val source = controlFunction.clockSources[sourceId] ?: return null
                AndroidUac2ClockSelectorCandidate(index + 1, source)
            }
            if (sources.isEmpty()) return null
            clockSource = sources.first().clockSource
            clockSelector = AndroidUac2ClockSelector(
                controlInterfaceNumber = controlFunction.interfaceNumber,
                clockSelectorId = parsedSelector.clockSelectorId,
                selectionAccess = parsedSelector.selectionAccess,
            )
            clockSourceCandidates = sources
        }

        else -> return null
    }

    val isochronousOutData = interfaceDescriptor.endpoints.filter {
        it.isIsochronous && it.direction == UsbEndpointDirection.Out && it.usageType == USB_ISO_USAGE_DATA
    }
    if (isochronousOutData.isEmpty()) return null
    if (isochronousOutData.size != 1) return null
    val feedbackEndpoints = interfaceDescriptor.endpoints.filter {
        it.isIsochronous && it.direction == UsbEndpointDirection.In && it.usageType == USB_ISO_USAGE_FEEDBACK
    }
    if (feedbackEndpoints.size > 1 ||
        (feedbackEndpoints.isNotEmpty() && isochronousOutData.single().synchronizationType != USB_ISO_SYNC_ASYNCHRONOUS)
    ) return null

    return AndroidUac2PlaybackAltSetting(
        configurationValue = configurationValue,
        controlInterfaceNumber = clockSource.controlInterfaceNumber,
        interfaceNumber = interfaceDescriptor.number,
        alternateSetting = interfaceDescriptor.alternateSetting,
        terminalLink = terminalLink,
        clockSourceId = clockSource.clockSourceId,
        channelCount = channelCount,
        channelConfig = channelConfig,
        clockFrequencyAccess = clockSource.frequencyAccess,
        subslotSizeBytes = subslotSizeBytes,
        validBitResolution = validBitResolution,
        dataEndpoint = isochronousOutData.single().toPublicEndpoint(),
        feedbackEndpoint = feedbackEndpoints.singleOrNull()?.toPublicEndpoint(),
        clockSelector = clockSelector,
        clockSourceCandidates = clockSourceCandidates,
    )
}

private fun parseEndpoint(
    address: Int,
    attributes: Int,
    rawMaximumPacketSize: Int,
    interval: Int,
): ParsedEndpoint? {
    if (address and USB_ENDPOINT_RESERVED_ADDRESS_BITS != 0 || address and USB_ENDPOINT_NUMBER_MASK == 0) return null
    val transferType = attributes and USB_ENDPOINT_TRANSFER_TYPE_MASK
    val synchronizationType = (attributes and USB_ENDPOINT_SYNCHRONIZATION_MASK) ushr 2
    val usageType = (attributes and USB_ENDPOINT_USAGE_MASK) ushr 4
    if (attributes and USB_ENDPOINT_RESERVED_ATTRIBUTE_BITS != 0) return null
    if (transferType == USB_TRANSFER_TYPE_ISOCHRONOUS) {
        when (usageType) {
            USB_ISO_USAGE_FEEDBACK -> {
                if (synchronizationType != USB_ISO_SYNC_NONE || address and USB_ENDPOINT_DIRECTION_IN == 0) return null
            }
            USB_ISO_USAGE_DATA, USB_ISO_USAGE_IMPLICIT_FEEDBACK -> {
                if (synchronizationType !in USB_ISO_SYNC_ASYNCHRONOUS..USB_ISO_SYNC_SYNCHRONOUS) return null
            }
            else -> return null
        }
    } else if (synchronizationType != USB_ISO_SYNC_NONE || usageType != USB_ISO_USAGE_DATA) {
        return null
    }
    val packetSizeBytes = rawMaximumPacketSize and USB_ENDPOINT_PACKET_SIZE_MASK
    val transactionsCode = (rawMaximumPacketSize and USB_ENDPOINT_TRANSACTION_MULTIPLIER_MASK) ushr 11
    val intervalIsValid = when (transferType) {
        USB_TRANSFER_TYPE_ISOCHRONOUS -> interval in 1..USB_ENDPOINT_MAX_INTERVAL
        USB_TRANSFER_TYPE_INTERRUPT -> interval > 0
        else -> true // Full-speed bulk endpoints may advertise bInterval = 0.
    }
    if (rawMaximumPacketSize and USB_ENDPOINT_RESERVED_PACKET_BITS != 0 ||
        packetSizeBytes == 0 ||
        (transferType == USB_TRANSFER_TYPE_ISOCHRONOUS && packetSizeBytes > USB_ISOCHRONOUS_MAX_PACKET_BYTES) ||
        transactionsCode == USB_ENDPOINT_RESERVED_TRANSACTION_CODE || !intervalIsValid
    ) return null
    return ParsedEndpoint(
        address = address,
        direction = if (address and USB_ENDPOINT_DIRECTION_IN != 0) UsbEndpointDirection.In else UsbEndpointDirection.Out,
        transferType = transferType,
        synchronizationType = synchronizationType,
        usageType = usageType,
        maximumPacketSizeBytes = packetSizeBytes,
        transactionsPerMicroframe = transactionsCode + 1,
        interval = interval,
    )
}

private fun ParsedEndpoint.toPublicEndpoint() = AndroidUac2IsochronousEndpoint(
    address = address,
    synchronizationType = synchronizationType,
    usageType = usageType,
    maximumPacketSizeBytes = maximumPacketSizeBytes,
    transactionsPerMicroframe = transactionsPerMicroframe,
    interval = interval,
)

private val ParsedEndpoint.isIsochronous: Boolean get() = transferType == USB_TRANSFER_TYPE_ISOCHRONOUS

private fun readUnsignedLittleEndian(data: ByteArray, offset: Int, count: Int): Long =
    (0 until count).fold(0L) { result, byte ->
        result or ((data[offset + byte].toInt() and 0xff).toLong() shl (byte * 8))
    }

private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff
private fun ByteArray.u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

private data class UsbInterfaceBuilder(
    val number: Int,
    val alternateSetting: Int,
    val declaredEndpointCount: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: MutableList<ParsedEndpoint> = mutableListOf(),
    val classSpecificInterfaces: MutableList<ClassSpecificInterfaceDescriptor> = mutableListOf(),
)

private data class ClassSpecificInterfaceDescriptor(
    val offset: Int,
    val length: Int,
    val subtype: Int,
    val bytes: ByteArray,
)

private data class ParsedEndpoint(
    val address: Int,
    val direction: UsbEndpointDirection,
    val transferType: Int,
    val synchronizationType: Int,
    val usageType: Int,
    val maximumPacketSizeBytes: Int,
    val transactionsPerMicroframe: Int,
    val interval: Int,
)

private enum class UsbEndpointDirection { In, Out }

private data class AudioFunctionAssociation(
    val firstInterface: Int,
    val interfaceCount: Int,
    val protocol: Int,
)

private data class Uac2InputTerminal(
    val terminalType: Int,
    val clockSourceId: Int,
    val channelCount: Int,
)

private data class ParsedUac2ControlFunction(
    val interfaceNumber: Int,
    val inputTerminals: Map<Int, Uac2InputTerminal>,
    val clockSources: Map<Int, AndroidUac2ClockSource>,
    val clockSelectors: Map<Int, ParsedUac2ClockSelector>,
    val entitySubtypes: Map<Int, Int>,
)

private data class ParsedUac2ClockSelector(
    val clockSelectorId: Int,
    val inputClockSourceIds: List<Int>,
    val selectionAccess: AndroidUac2ClockFrequencyAccess,
)

private const val USB_CLASS_INTERFACE_IN = 0xa1
private const val USB_CLASS_INTERFACE_OUT = 0x21
private const val USB_CLASS_AUDIO = 0x01
private const val USB_SUBCLASS_AUDIO_CONTROL = 0x01
private const val USB_SUBCLASS_AUDIO_STREAMING = 0x02
private const val USB_FUNCTION_SUBCLASS_UNDEFINED = 0x00
private const val USB_PROTOCOL_UAC2 = 0x20
private const val USB_TERMINAL_TYPE_USB_STREAMING = 0x0101

private const val USB_DESCRIPTOR_CONFIGURATION = 0x02
private const val USB_DESCRIPTOR_DEVICE = 0x01
private const val USB_DESCRIPTOR_INTERFACE = 0x04
private const val USB_DESCRIPTOR_ENDPOINT = 0x05
private const val USB_DESCRIPTOR_INTERFACE_ASSOCIATION = 0x0b
private const val USB_DESCRIPTOR_CS_INTERFACE = 0x24

private const val UAC_AC_HEADER = 0x01
private const val UAC_AC_INPUT_TERMINAL = 0x02
private const val UAC_AC_OUTPUT_TERMINAL = 0x03
private const val UAC_AC_CLOCK_SOURCE = 0x0a
private const val UAC_AC_CLOCK_SELECTOR = 0x0b
private const val UAC_AC_CLOCK_MULTIPLIER = 0x0c
private const val UAC_AS_GENERAL = 0x01
private const val UAC_AS_FORMAT_TYPE = 0x02
private const val UAC_FORMAT_TYPE_I = 0x01
private const val UAC_VERSION_MAJOR_2 = 0x02
private const val UAC2_PCM_FORMAT_BIT = 1L
private const val UAC2_CLOCK_FREQUENCY_CONTROL_MASK = 0x03
private const val UAC2_CONTROL_READ_ONLY = 0x01
private const val UAC2_CONTROL_READ_WRITE = 0x03
private const val UAC2_CLOCK_FREQUENCY_CONTROL_SELECTOR = 0x01
private const val UAC2_CLOCK_SELECTOR_CONTROL_SELECTOR = 0x01
private const val UAC2_CLOCK_SELECTOR_SELECTION_CONTROL_MASK = 0x03
private const val UAC2_CLOCK_SELECTOR_RESERVED_CONTROL_MASK = 0xfc
private const val UAC2_GET_RANGE = 0x02
private const val UAC2_GET_CUR = 0x01
private const val UAC2_SET_CUR = 0x01
private const val UAC2_CLOCK_FREQUENCY_BYTES = 4
private const val UAC2_RANGE_HEADER_BYTES = 2
private const val UAC2_CLOCK_RANGE_BYTES = 12
private const val UAC2_CLOCK_SOURCE_DESCRIPTOR_BYTES = 8
private const val UAC2_CLOCK_SELECTOR_MIN_BYTES = 8
private const val UAC2_CLOCK_SELECTOR_FIXED_BYTES = 7
private const val UAC2_CLOCK_MULTIPLIER_DESCRIPTOR_BYTES = 7
private const val UAC2_AC_HEADER_DESCRIPTOR_BYTES = 9
private const val UAC2_INPUT_TERMINAL_DESCRIPTOR_BYTES = 17
private const val UAC2_OUTPUT_TERMINAL_DESCRIPTOR_BYTES = 12
private const val UAC2_AS_GENERAL_DESCRIPTOR_BYTES = 16
private const val UAC2_TYPE_I_FORMAT_DESCRIPTOR_BYTES = 6
private val UAC_AC_ENTITY_SUBTYPE_RANGE = 0x02..0x0c
private const val MAX_USB_CONFIGURATION_BYTES = 65_535
private const val MAX_USB_CONFIGURATIONS = 0xff
private const val MAX_USB_RAW_DESCRIPTOR_BYTES = MAX_USB_CONFIGURATION_BYTES * MAX_USB_CONFIGURATIONS + 18
private const val MAX_USB_DESCRIPTORS = 2_048
private const val MAX_USB_INTERFACE_NUMBER_EXCLUSIVE = 256
private const val MAX_UAC2_PCM_CHANNELS = 32
private const val MAX_UAC2_CLOCK_SUBRANGES = 64
private const val UAC2_UINT32_MAX_HZ = 0xffff_ffffL

private const val USB_CONFIGURATION_DESCRIPTOR_BYTES = 9
private const val USB_DEVICE_DESCRIPTOR_BYTES = 18
private const val USB_INTERFACE_DESCRIPTOR_BYTES = 9
private const val USB_INTERFACE_ASSOCIATION_DESCRIPTOR_BYTES = 8
private const val USB_ENDPOINT_DESCRIPTOR_BYTES = 7
private const val USB_ENDPOINT_RESERVED_ADDRESS_BITS = 0x70
private const val USB_ENDPOINT_NUMBER_MASK = 0x0f
private const val USB_ENDPOINT_DIRECTION_IN = 0x80
private const val USB_ENDPOINT_TRANSFER_TYPE_MASK = 0x03
private const val USB_ENDPOINT_SYNCHRONIZATION_MASK = 0x0c
private const val USB_ENDPOINT_USAGE_MASK = 0x30
private const val USB_ENDPOINT_RESERVED_ATTRIBUTE_BITS = 0xc0
private const val USB_TRANSFER_TYPE_ISOCHRONOUS = 0x01
private const val USB_TRANSFER_TYPE_INTERRUPT = 0x03
private const val USB_ISO_SYNC_NONE = 0x00
private const val USB_ISO_SYNC_ASYNCHRONOUS = 0x01
private const val USB_ISO_SYNC_ADAPTIVE = 0x02
private const val USB_ISO_SYNC_SYNCHRONOUS = 0x03
private const val USB_ISO_USAGE_DATA = 0x00
private const val USB_ISO_USAGE_FEEDBACK = 0x01
private const val USB_ISO_USAGE_IMPLICIT_FEEDBACK = 0x02
private const val USB_ENDPOINT_PACKET_SIZE_MASK = 0x07ff
private const val USB_ENDPOINT_TRANSACTION_MULTIPLIER_MASK = 0x1800
private const val USB_ENDPOINT_RESERVED_PACKET_BITS = 0xe000
private const val USB_ENDPOINT_RESERVED_TRANSACTION_CODE = 0x03
private const val USB_ENDPOINT_MAX_INTERVAL = 16
private const val USB_ISOCHRONOUS_MAX_PACKET_BYTES = 1024
