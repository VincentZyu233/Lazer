package dev.naominet.lazer

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/**
 * Configuration reached by this session. `ConfiguredNotStreaming` is deliberately not called
 * Playing: Android's public USB API does not submit UAC2 isochronous transfers.
 */
internal enum class AndroidUac2PlaybackSessionState {
    New,
    Configuring,
    ConfiguredNotStreaming,
    Failed,
    Detached,
    Closed,
}

/** USB operations used by [AndroidUac2PlaybackSession], split out for deterministic lifecycle tests. */
internal interface AndroidUac2PlaybackSessionGateway {
    fun hasPermission(device: UsbDevice): Boolean
    fun openDevice(device: UsbDevice): UsbDeviceConnection?
    fun setConfiguration(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        configurationValue: Int,
    ): Boolean

    fun validateStreamEndpoints(
        device: UsbDevice,
        plan: AndroidUac2PlaybackStreamPlan,
    ): Boolean

    fun claimInterface(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        configurationValue: Int,
        interfaceNumber: Int,
        interfaceSubclass: Int,
        alternateSetting: Int,
    ): Boolean

    fun transfer(connection: UsbDeviceConnection, request: AndroidUsbControlRequest): Int

    fun setInterface(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        configurationValue: Int,
        interfaceNumber: Int,
        interfaceSubclass: Int,
        alternateSetting: Int,
    ): Boolean

    fun releaseInterface(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        configurationValue: Int,
        interfaceNumber: Int,
        interfaceSubclass: Int,
    ): Boolean

    fun closeConnection(connection: UsbDeviceConnection)
}

/** Android framework implementation. Interface numbers and alternate settings are resolved from
 * the selected device's descriptors and must match the values retained by the parsed stream plan.
 */
internal class AndroidFrameworkUac2PlaybackSessionGateway(
    private val usbManager: UsbManager,
) : AndroidUac2PlaybackSessionGateway {
    override fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    override fun openDevice(device: UsbDevice): UsbDeviceConnection? = usbManager.openDevice(device)

    override fun setConfiguration(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        configurationValue: Int,
    ): Boolean {
        val configuration = device.findConfiguration(configurationValue) ?: return false
        return connection.setConfiguration(configuration)
    }

    override fun validateStreamEndpoints(
        device: UsbDevice,
        plan: AndroidUac2PlaybackStreamPlan,
    ): Boolean {
        val streamInterface = device.findAudioInterface(
            configurationValue = plan.configurationValue,
            interfaceNumber = plan.interfaceNumber,
            interfaceSubclass = USB_SUBCLASS_AUDIO_STREAMING,
            alternateSetting = plan.alternateSetting,
        ) ?: return false
        val endpoints = (0 until streamInterface.endpointCount).map(streamInterface::getEndpoint)
        return androidUac2EndpointSetMatchesPlan(endpoints, plan)
    }

    override fun claimInterface(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        configurationValue: Int,
        interfaceNumber: Int,
        interfaceSubclass: Int,
        alternateSetting: Int,
    ): Boolean {
        val interfaceDescriptor = device.findAudioInterface(
            configurationValue = configurationValue,
            interfaceNumber = interfaceNumber,
            interfaceSubclass = interfaceSubclass,
            alternateSetting = alternateSetting,
        ) ?: return false
        return connection.claimInterface(interfaceDescriptor, true)
    }

    override fun transfer(connection: UsbDeviceConnection, request: AndroidUsbControlRequest): Int =
        AndroidUsbConnectionControlTransfer(connection).transfer(request)

    override fun setInterface(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        configurationValue: Int,
        interfaceNumber: Int,
        interfaceSubclass: Int,
        alternateSetting: Int,
    ): Boolean {
        val interfaceDescriptor = device.findAudioInterface(
            configurationValue = configurationValue,
            interfaceNumber = interfaceNumber,
            interfaceSubclass = interfaceSubclass,
            alternateSetting = alternateSetting,
        ) ?: return false
        return connection.setInterface(interfaceDescriptor)
    }

    override fun releaseInterface(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        configurationValue: Int,
        interfaceNumber: Int,
        interfaceSubclass: Int,
    ): Boolean {
        val alternateZero = device.findAudioInterface(
            configurationValue = configurationValue,
            interfaceNumber = interfaceNumber,
            interfaceSubclass = interfaceSubclass,
            alternateSetting = 0,
        ) ?: return false
        return connection.releaseInterface(alternateZero)
    }

    override fun closeConnection(connection: UsbDeviceConnection) = connection.close()

    private fun UsbDevice.findConfiguration(configurationValue: Int): UsbConfiguration? {
        val matches = (0 until configurationCount)
            .map(::getConfiguration)
            .filter { it.id == configurationValue }
        return matches.singleOrNull()
    }

    private fun UsbDevice.findAudioInterface(
        configurationValue: Int,
        interfaceNumber: Int,
        interfaceSubclass: Int,
        alternateSetting: Int,
    ): UsbInterface? {
        val configuration = findConfiguration(configurationValue) ?: return null
        val matches = (0 until configuration.interfaceCount)
            .map(configuration::getInterface)
            .filter {
                it.id == interfaceNumber &&
                    it.alternateSetting == alternateSetting &&
                    it.interfaceClass == USB_CLASS_AUDIO &&
                    it.interfaceProtocol == USB_PROTOCOL_UAC2 &&
                    it.interfaceSubclass == interfaceSubclass
            }
        return matches.singleOrNull()
    }
}

/**
 * Opens and configures one UAC2 PCM playback plan while retaining the USB connection for a later
 * native transport. The caller must invoke [openAndConfigure] off the main thread. On success the
 * control interface remains claimed and the streaming interface is at the plan's alternate
 * setting, but no audio packets have been sent. If a native transport has borrowed the connection
 * descriptor, its owner must synchronously stop and close that transport before calling [close] or
 * [onDeviceDetached]; native transfers and event callbacks must be gone before this session releases
 * claims or closes the Android connection.
 */
internal class AndroidUac2PlaybackSession(
    private val device: UsbDevice,
    private val plan: AndroidUac2PlaybackStreamPlan,
    private val gateway: AndroidUac2PlaybackSessionGateway,
) : AutoCloseable {
    private val claimedInterfaces = linkedSetOf<Int>()
    private var clockRanges: List<AndroidUac2ClockFrequencyRange> = emptyList()
    private var activeClockSource: AndroidUac2ClockSource? = null
    private var activeClockSelector: AndroidUac2ClockSelector? = null
    private var originalClockSelectorPin: Int? = null
    private var originalClockFrequencyHz: Long? = null
    private var clockWriteMayHaveChangedValue = false
    private var selectorWriteMayHaveChangedValue = false
    private var streamingAlternateMayBeSelected = false
    private var previousConfigurationValue: Int? = null
    private var configurationMayHaveChanged = false
    private var failure: Throwable? = null

    /** Held for the full configured session so a future libusb bridge can wrap its descriptor. */
    private var connection: UsbDeviceConnection? = null

    var state: AndroidUac2PlaybackSessionState = AndroidUac2PlaybackSessionState.New
        private set

    /** First cleanup error from close/detach; open failures are thrown with rollback errors added. */
    var cleanupFailure: Throwable? = null
        private set

    @Synchronized
    fun openAndConfigure(): AndroidUac2PlaybackSessionState {
        when (state) {
            AndroidUac2PlaybackSessionState.ConfiguredNotStreaming -> return state
            AndroidUac2PlaybackSessionState.New -> Unit
            AndroidUac2PlaybackSessionState.Failed -> throw checkNotNull(failure)
            else -> error("Cannot open UAC2 playback session in state $state")
        }

        state = AndroidUac2PlaybackSessionState.Configuring
        try {
            check(gateway.hasPermission(device)) { "USB permission is not granted for the selected device" }
            connection = gateway.openDevice(device)
                ?: throw IOException("USB device could not be opened")

            check(gateway.validateStreamEndpoints(device, plan)) {
                "Android USB endpoints do not match the selected UAC2 stream plan"
            }

            previousConfigurationValue = readCurrentConfiguration()
            if (previousConfigurationValue != plan.configurationValue) {
                // A failed setConfiguration call may still have changed the device. Roll back to
                // the value read from GET_CONFIGURATION even when Android reports failure.
                configurationMayHaveChanged = true
                check(gateway.setConfiguration(requireConnection(), device, plan.configurationValue)) {
                    "USB configuration ${plan.configurationValue} could not be selected"
                }
            }
            claim(
                interfaceNumber = plan.controlInterfaceNumber,
                interfaceSubclass = USB_SUBCLASS_AUDIO_CONTROL,
                alternateSetting = 0,
            )
            claim(
                interfaceNumber = plan.interfaceNumber,
                interfaceSubclass = USB_SUBCLASS_AUDIO_STREAMING,
                alternateSetting = 0,
            )

            val clockSource = resolveClockSourceForRequestedRate()
            activeClockSource = clockSource
            originalClockFrequencyHz = readCurrentClockFrequency(clockSource)
            if (originalClockFrequencyHz != plan.sampleRateHz) {
                check(clockSource.frequencyAccess == AndroidUac2ClockFrequencyAccess.HostProgrammable) {
                    "Clock Source is read-only at ${originalClockFrequencyHz} Hz; " +
                        "it cannot be configured for ${plan.sampleRateHz} Hz"
                }
                setClockFrequency(clockSource, plan.sampleRateHz)
                val acceptedFrequency = readCurrentClockFrequency(clockSource)
                check(acceptedFrequency == plan.sampleRateHz) {
                    "Clock Source accepted $acceptedFrequency Hz instead of ${plan.sampleRateHz} Hz"
                }
            }

            // Recheck before selecting the descriptor-backed data alternate.
            check(gateway.validateStreamEndpoints(device, plan)) {
                "Android USB endpoints changed while configuring the UAC2 stream"
            }
            // Claim alternate zero first; only then select the descriptor-backed data alternate.
            streamingAlternateMayBeSelected = true
            check(
                gateway.setInterface(
                    requireConnection(),
                    device,
                    plan.configurationValue,
                    plan.interfaceNumber,
                    USB_SUBCLASS_AUDIO_STREAMING,
                    plan.alternateSetting,
                ),
            ) {
                "AudioStreaming interface ${plan.interfaceNumber} alternate ${plan.alternateSetting} failed"
            }
            state = AndroidUac2PlaybackSessionState.ConfiguredNotStreaming
            return state
        } catch (error: Throwable) {
            failure = error
            cleanup(restoreDeviceFormat = true)
            state = AndroidUac2PlaybackSessionState.Failed
            throw error
        }
    }

    /** Returns the retained connection only after successful configuration, for a future native
     * isochronous transport. This method does not imply that streaming has started.
     */
    @Synchronized
    fun requireConfiguredConnection(): UsbDeviceConnection {
        check(state == AndroidUac2PlaybackSessionState.ConfiguredNotStreaming) {
            "USB connection is not configured for playback (state=$state)"
        }
        return requireConnection()
    }

    /** Device-detach callback: stop touching device controls, release local claims and close fd. */
    @Synchronized
    fun onDeviceDetached() {
        if (state.isTerminal()) return
        cleanup(restoreDeviceFormat = false)
        state = AndroidUac2PlaybackSessionState.Detached
    }

    /** Restores the clock and alternate setting where possible, then releases every owned handle. */
    @Synchronized
    override fun close() {
        if (state.isTerminal()) return
        cleanup(restoreDeviceFormat = true)
        state = AndroidUac2PlaybackSessionState.Closed
    }

    private fun claim(interfaceNumber: Int, interfaceSubclass: Int, alternateSetting: Int) {
        check(
            gateway.claimInterface(
                requireConnection(),
                device,
                plan.configurationValue,
                interfaceNumber,
                interfaceSubclass,
                alternateSetting,
            ),
        ) {
            "USB Audio interface $interfaceNumber alternate $alternateSetting could not be claimed"
        }
        claimedInterfaces += interfaceNumber
    }

    private fun readClockRanges(source: AndroidUac2ClockSource): List<AndroidUac2ClockFrequencyRange> {
        val headerRequest = androidUac2ClockSourceGetRangeHeaderRequest(source)
        check(transfer(headerRequest) == headerRequest.length) { "Clock Source GET_RANGE header failed" }
        val subRangeCount = parseAndroidUac2ClockSourceGetRangeSubRangeCount(headerRequest.data)
        val rangeRequest = androidUac2ClockSourceGetRangeRequest(source, subRangeCount)
        check(transfer(rangeRequest) == rangeRequest.length) { "Clock Source GET_RANGE failed" }
        return parseAndroidUac2ClockSourceFrequencyRanges(rangeRequest.data, subRangeCount)
    }

    private fun resolveClockSourceForRequestedRate(): AndroidUac2ClockSource {
        val selector = plan.clockSelector
        if (selector == null) {
            val source = AndroidUac2ClockSource(
                controlInterfaceNumber = plan.controlInterfaceNumber,
                clockSourceId = plan.clockSourceId,
                frequencyAccess = plan.clockFrequencyAccess,
            )
            clockRanges = readClockRanges(source)
            check(clockRanges.any { it.contains(plan.sampleRateHz) }) {
                "Clock Source does not advertise ${plan.sampleRateHz} Hz"
            }
            return source
        }

        val candidates = plan.clockSourceCandidates
        check(candidates.isNotEmpty()) { "Clock Selector has no supported Clock Source inputs" }
        activeClockSelector = selector
        val currentPin = readCurrentClockSelectorPin(selector, candidates.size)
        originalClockSelectorPin = currentPin
        val rangesByPin = candidates.associate { candidate ->
            candidate.pin to readClockRanges(candidate.clockSource)
        }
        val selection = planAndroidUac2ClockSelectorSelection(
            selector = selector,
            candidates = candidates,
            currentPin = currentPin,
            supportedRangesByPin = rangesByPin,
            sampleRateHz = plan.sampleRateHz,
        ) ?: throw IllegalStateException(
            "No permitted Clock Selector input advertises ${plan.sampleRateHz} Hz",
        )

        if (selection.pin != currentPin) {
            // A short or failed write may still have switched the device. Restore the original pin
            // during rollback/close and verify its readback before releasing the control interface.
            selectorWriteMayHaveChangedValue = true
            val request = androidUac2ClockSelectorSetCurrentPinRequest(selector, selection.pin, candidates.size)
            check(transfer(request) == request.length) { "Clock Selector SET_CUR failed" }
            val acceptedPin = readCurrentClockSelectorPin(selector, candidates.size)
            check(acceptedPin == selection.pin) {
                "Clock Selector accepted input $acceptedPin instead of ${selection.pin}"
            }
        }
        clockRanges = checkNotNull(rangesByPin[selection.pin])
        check(clockRanges.any { it.contains(plan.sampleRateHz) }) {
            "Selected Clock Source does not advertise ${plan.sampleRateHz} Hz"
        }
        return selection.clockSource
    }

    private fun readCurrentClockSelectorPin(selector: AndroidUac2ClockSelector, candidateCount: Int): Int {
        val request = androidUac2ClockSelectorGetCurrentPinRequest(selector)
        check(transfer(request) == request.length) { "Clock Selector GET_CUR failed" }
        return parseAndroidUac2ClockSelectorCurrentPin(request.data, candidateCount)
    }

    private fun readCurrentConfiguration(): Int {
        val request = AndroidUsbControlRequest(
            requestType = USB_STANDARD_DEVICE_IN,
            request = USB_REQUEST_GET_CONFIGURATION,
            value = 0,
            index = 0,
            data = byteArrayOf(0),
            length = 1,
        )
        check(transfer(request) == 1) { "USB GET_CONFIGURATION failed or returned a short response" }
        return request.data[0].toInt() and 0xff
    }

    private fun readCurrentClockFrequency(source: AndroidUac2ClockSource): Long {
        val request = androidUac2ClockSourceGetCurrentFrequencyRequest(source)
        check(transfer(request) == request.length) { "Clock Source GET_CUR failed" }
        return parseAndroidUac2ClockSourceCurrentFrequencyHz(request.data)
    }

    private fun setClockFrequency(source: AndroidUac2ClockSource, sampleRateHz: Long) {
        val request = androidUac2ClockSourceSetCurrentFrequencyRequest(source, sampleRateHz, clockRanges)
        // Treat a short/failed write as possibly applied. Rollback reads the device before closing.
        clockWriteMayHaveChangedValue = true
        check(transfer(request) == request.length) { "Clock Source SET_CUR failed" }
    }

    private fun transfer(request: AndroidUsbControlRequest): Int =
        gateway.transfer(requireConnection(), request)

    private fun requireConnection(): UsbDeviceConnection =
        checkNotNull(connection) { "USB device connection is not open" }

    private fun cleanup(restoreDeviceFormat: Boolean) {
        val activeConnection = connection ?: return
        fun attempt(action: () -> Unit) {
            runCatching(action).exceptionOrNull()?.let { error ->
                val current = cleanupFailure
                if (current == null) cleanupFailure = error else current.addSuppressed(error)
                failure?.let { original -> if (original !== error) original.addSuppressed(error) }
            }
        }

        if (restoreDeviceFormat && streamingAlternateMayBeSelected) {
            attempt {
                check(
                    gateway.setInterface(
                        activeConnection,
                        device,
                        plan.configurationValue,
                        plan.interfaceNumber,
                        USB_SUBCLASS_AUDIO_STREAMING,
                        0,
                    ),
                ) { "AudioStreaming interface could not be restored to alternate zero" }
            }
        }
        streamingAlternateMayBeSelected = false

        if (restoreDeviceFormat && clockWriteMayHaveChangedValue) {
            val originalFrequency = originalClockFrequencyHz
            val source = activeClockSource
            if (originalFrequency != null && source != null) {
                attempt {
                    setClockFrequency(source, originalFrequency)
                    check(readCurrentClockFrequency(source) == originalFrequency) {
                        "Clock Source did not restore its original $originalFrequency Hz rate"
                    }
                }
            }
        }
        clockWriteMayHaveChangedValue = false

        if (restoreDeviceFormat && selectorWriteMayHaveChangedValue) {
            val selector = activeClockSelector
            val originalPin = originalClockSelectorPin
            if (selector != null && originalPin != null) {
                attempt {
                    val request = androidUac2ClockSelectorSetCurrentPinRequest(
                        selector,
                        originalPin,
                        plan.clockSourceCandidates.size,
                    )
                    check(transfer(request) == request.length) { "Clock Selector restore SET_CUR failed" }
                    check(readCurrentClockSelectorPin(selector, plan.clockSourceCandidates.size) == originalPin) {
                        "Clock Selector did not restore its original input $originalPin"
                    }
                }
            }
        }
        selectorWriteMayHaveChangedValue = false

        if (restoreDeviceFormat && configurationMayHaveChanged) {
            val previousConfiguration = previousConfigurationValue
            if (previousConfiguration != null) {
                attempt {
                    val request = AndroidUsbControlRequest(
                        requestType = USB_STANDARD_DEVICE_OUT,
                        request = USB_REQUEST_SET_CONFIGURATION,
                        value = previousConfiguration,
                        index = 0,
                        data = byteArrayOf(),
                        length = 0,
                    )
                    check(gateway.transfer(activeConnection, request) == 0) {
                        "USB configuration $previousConfiguration could not be restored"
                    }
                }
            }
        }
        configurationMayHaveChanged = false

        claimedInterfaces.toList().asReversed().forEach { interfaceNumber ->
            attempt {
                check(
                    gateway.releaseInterface(
                        activeConnection,
                        device,
                        plan.configurationValue,
                        interfaceNumber,
                        if (interfaceNumber == plan.controlInterfaceNumber) {
                            USB_SUBCLASS_AUDIO_CONTROL
                        } else {
                            USB_SUBCLASS_AUDIO_STREAMING
                        },
                    ),
                ) { "USB Audio interface $interfaceNumber could not be released" }
            }
        }
        claimedInterfaces.clear()
        attempt { gateway.closeConnection(activeConnection) }
        connection = null
    }

    private fun AndroidUac2PlaybackSessionState.isTerminal(): Boolean =
        this == AndroidUac2PlaybackSessionState.Failed ||
            this == AndroidUac2PlaybackSessionState.Detached ||
            this == AndroidUac2PlaybackSessionState.Closed
}

private const val USB_CLASS_AUDIO = 0x01
private const val USB_SUBCLASS_AUDIO_CONTROL = 0x01
private const val USB_SUBCLASS_AUDIO_STREAMING = 0x02
private const val USB_PROTOCOL_UAC2 = 0x20

/** Verifies Android's exposed endpoint objects against every transport-relevant plan field. */
internal fun androidUac2EndpointSetMatchesPlan(
    endpoints: List<UsbEndpoint>,
    plan: AndroidUac2PlaybackStreamPlan,
): Boolean {
    val expected = buildList {
        add(plan.dataEndpoint to USB_DIR_OUT)
        plan.feedbackEndpoint?.let { add(it to USB_DIR_IN) }
    }
    if (expected.map { it.first.address }.distinct().size != expected.size) return false

    val isochronousEndpoints = endpoints.filter {
        it.attributes and USB_ENDPOINT_TRANSFER_TYPE_MASK == USB_TRANSFER_TYPE_ISOCHRONOUS
    }
    if (isochronousEndpoints.size != expected.size) return false
    if (isochronousEndpoints.map(UsbEndpoint::getAddress).toSet() != expected.map { it.first.address }.toSet()) {
        return false
    }

    return expected.all { (descriptor, expectedDirection) ->
        val endpoint = isochronousEndpoints.singleOrNull { it.address == descriptor.address }
            ?: return@all false
        val attributes = endpoint.attributes
        // AOSP UsbEndpointDescriptor forwards raw wMaxPacketSize to UsbEndpoint and
        // UsbEndpoint.getMaxPacketSize() returns that field unchanged, including HS bits 11..12.
        // Decode both the payload size and the additional-transaction code here.
        val rawPacketSize = endpoint.maxPacketSize
        if (attributes and USB_ENDPOINT_RESERVED_ATTRIBUTE_BITS != 0 ||
            rawPacketSize < 0 ||
            rawPacketSize and USB_ENDPOINT_RESERVED_PACKET_BITS != 0
        ) return@all false

        val transactionsCode = (rawPacketSize and USB_ENDPOINT_TRANSACTION_MULTIPLIER_MASK) ushr 11
        if (transactionsCode == USB_ENDPOINT_RESERVED_TRANSACTION_CODE) return@all false
        val packetSizeBytes = rawPacketSize and USB_ENDPOINT_PACKET_SIZE_MASK
        val transactionsPerMicroframe = transactionsCode + 1
        val synchronizationType = (attributes and USB_ENDPOINT_SYNCHRONIZATION_MASK) ushr 2
        val usageType = (attributes and USB_ENDPOINT_USAGE_MASK) ushr 4

        endpoint.type == USB_TRANSFER_TYPE_ISOCHRONOUS &&
            endpoint.direction == expectedDirection &&
            (endpoint.address and USB_ENDPOINT_IN_MASK) == expectedDirection &&
            synchronizationType == descriptor.synchronizationType &&
            usageType == descriptor.usageType &&
            packetSizeBytes == descriptor.maximumPacketSizeBytes &&
            transactionsPerMicroframe == descriptor.transactionsPerMicroframe &&
            endpoint.interval == descriptor.interval
    }
}

private const val USB_STANDARD_DEVICE_IN = 0x80
private const val USB_STANDARD_DEVICE_OUT = 0x00
private const val USB_REQUEST_GET_CONFIGURATION = 0x08
private const val USB_REQUEST_SET_CONFIGURATION = 0x09
private const val USB_DIR_OUT = 0x00
private const val USB_DIR_IN = 0x80
private const val USB_ENDPOINT_IN_MASK = 0x80
private const val USB_ENDPOINT_TRANSFER_TYPE_MASK = 0x03
private const val USB_TRANSFER_TYPE_ISOCHRONOUS = 0x01
private const val USB_ENDPOINT_SYNCHRONIZATION_MASK = 0x0c
private const val USB_ENDPOINT_USAGE_MASK = 0x30
private const val USB_ENDPOINT_RESERVED_ATTRIBUTE_BITS = 0xc0
private const val USB_ENDPOINT_PACKET_SIZE_MASK = 0x07ff
private const val USB_ENDPOINT_TRANSACTION_MULTIPLIER_MASK = 0x1800
private const val USB_ENDPOINT_RESERVED_PACKET_BITS = 0xe000
private const val USB_ENDPOINT_RESERVED_TRANSACTION_CODE = 0x03
