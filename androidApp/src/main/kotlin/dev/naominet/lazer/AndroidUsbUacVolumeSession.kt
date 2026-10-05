package dev.naominet.lazer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AndroidUacDeviceIdentity(
    val id: String,
    val vendorId: Int,
    val productId: Int,
)

internal data class AndroidUacPermissionRequest(
    val device: AndroidUacDeviceIdentity,
    val increase: Boolean?,
    val generation: Long,
)

internal data class AndroidUacPermissionResult(
    val device: AndroidUacDeviceIdentity,
    val generation: Long?,
    val granted: Boolean,
)

internal interface AndroidUacVolumeGateway {
    fun findDevice(deviceId: String): AndroidUacDeviceIdentity?
    fun hasPermission(device: AndroidUacDeviceIdentity): Boolean
    fun requestPermission(request: AndroidUacPermissionRequest)

    /** Opens the selected USB device and performs the requested UAC transaction. */
    suspend fun perform(
        request: AndroidUacPermissionRequest,
        setCurrent: (transfer: () -> Int) -> Int,
    ): AndroidUacVolumeOperationResult
}

internal sealed interface AndroidUacVolumeOperationResult {
    data object Unsupported : AndroidUacVolumeOperationResult
    data class Ready(
        val volume: AndroidUacVolumeValue,
        val ranges: List<AndroidUacVolumeRange>,
    ) : AndroidUacVolumeOperationResult
}

/**
 * Coordinates USB permission broadcasts and UAC volume transactions without depending on Android
 * framework objects, so stale broadcasts and asynchronous device races can be tested on the JVM.
 */
internal class AndroidUsbUacVolumeSession(
    private val gateway: AndroidUacVolumeGateway,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    initialSnapshot: LazerUsbUacVolumeSnapshot = LazerUsbUacVolumeSnapshot(),
    private val refreshDevices: () -> List<LazerUsbUacDeviceOption> = { initialSnapshot.devices },
    private val publish: (LazerUsbUacVolumeSnapshot) -> Unit,
) {
    private val stateLock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var generation = 0L

    @Volatile
    private var selectedDeviceId: String? = initialSnapshot.selectedDeviceId

    @Volatile
    private var snapshot = initialSnapshot

    private var pendingRequest: AndroidUacPermissionRequest? = null

    fun currentSnapshot(): LazerUsbUacVolumeSnapshot = snapshot

    fun updateDevices(devices: List<LazerUsbUacDeviceOption>) {
        synchronized(stateLock) {
            if (closed) return
            val current = snapshot
            val selected = current.selectedDeviceId
            val replacement = selected?.let { id -> devices.singleOrNull { it.id == id } }
            val old = selected?.let { id -> current.devices.singleOrNull { it.id == id } }
            if (selected != null && (replacement == null || old == null ||
                    replacement.vendorId != old.vendorId || replacement.productId != old.productId)
            ) {
                invalidateRequests()
                selectedDeviceId = null
                publishSnapshot(
                    current.copy(
                        devices = devices,
                        selectedDeviceId = null,
                        status = LazerUsbUacVolumeStatus.Idle,
                        currentDb256 = null,
                        muted = false,
                        ranges = emptyList(),
                        canDecrease = false,
                        canIncrease = false,
                        detail = "settings.hifi.usb_uac.device_removed",
                    ),
                )
            } else {
                publishSnapshot(current.copy(devices = devices))
            }
        }
    }

    fun selectDevice(identity: String?) {
        synchronized(stateLock) {
            if (closed) return
            val current = snapshot
            if (identity != null && current.devices.none { it.id == identity }) return
            invalidateRequests()
            selectedDeviceId = identity
            publishSnapshot(
                LazerUsbUacVolumeSnapshot(
                    devices = current.devices,
                    selectedDeviceId = identity,
                ),
            )
        }
    }

    fun onDeviceDetached(deviceId: String) {
        synchronized(stateLock) {
            if (closed) return
            val current = snapshot
            if (current.selectedDeviceId != deviceId) return
            invalidateRequests()
            selectedDeviceId = null
            publishSnapshot(
                current.copy(
                    devices = current.devices.filterNot { it.id == deviceId },
                    selectedDeviceId = null,
                    status = LazerUsbUacVolumeStatus.Idle,
                    currentDb256 = null,
                    muted = false,
                    ranges = emptyList(),
                    canDecrease = false,
                    canIncrease = false,
                    detail = "settings.hifi.usb_uac.device_removed",
                ),
            )
        }
    }

    fun request(increase: Boolean?) {
        if (closed) return
        val current = snapshot
        if (current.status == LazerUsbUacVolumeStatus.AwaitingPermission ||
            current.status == LazerUsbUacVolumeStatus.Reading
        ) return
        val selectedId = current.selectedDeviceId ?: return
        val option = current.devices.singleOrNull { it.id == selectedId }
        val device = gateway.findDevice(selectedId)
        if (option == null || device == null || device.vendorId != option.vendorId ||
            device.productId != option.productId
        ) {
            refreshDeviceSnapshot()
            publishSnapshot(
                snapshot.copy(
                    status = LazerUsbUacVolumeStatus.Failed,
                    detail = "settings.hifi.usb_uac.device_removed",
                ),
            )
            return
        }

        generation += 1
        val request = AndroidUacPermissionRequest(device, increase, generation)
        if (runCatching { gateway.hasPermission(device) }.getOrDefault(false)) {
            perform(request)
            return
        }

        pendingRequest = request
        publishSnapshot(
            current.copy(
                status = LazerUsbUacVolumeStatus.AwaitingPermission,
                currentDb256 = null,
                muted = false,
                ranges = emptyList(),
                canDecrease = false,
                canIncrease = false,
                detail = "settings.hifi.usb_uac.permission_prompt",
            ),
        )
        runCatching { gateway.requestPermission(request) }.onFailure {
            if (!isCurrent(request)) return@onFailure
            pendingRequest = null
            publishSnapshot(
                snapshot.copy(
                    status = LazerUsbUacVolumeStatus.Failed,
                    detail = "settings.hifi.usb_uac.permission_failed",
                ),
            )
        }
    }

    fun onPermissionResult(result: AndroidUacPermissionResult) {
        if (closed) return
        val request = pendingRequest ?: return
        if (!permissionMatches(request, result)) return
        pendingRequest = null
        if (!result.granted) {
            publishSnapshot(
                snapshot.copy(
                    status = LazerUsbUacVolumeStatus.PermissionDenied,
                    detail = "settings.hifi.usb_uac.permission_denied",
                ),
            )
            return
        }
        val attachedDevice = gateway.findDevice(request.device.id)
        if (attachedDevice != request.device) {
            refreshDeviceSnapshot()
            return
        }
        if (!runCatching { gateway.hasPermission(request.device) }.getOrDefault(false)) {
            publishSnapshot(
                snapshot.copy(
                    status = LazerUsbUacVolumeStatus.PermissionDenied,
                    detail = "settings.hifi.usb_uac.permission_denied",
                ),
            )
            return
        }
        perform(request)
    }

    fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            invalidateRequests()
        }
    }

    private fun perform(request: AndroidUacPermissionRequest) {
        if (!isCurrent(request)) return
        pendingRequest = null
        publishSnapshot(
            snapshot.copy(
                status = LazerUsbUacVolumeStatus.Reading,
                detail = null,
                canDecrease = false,
                canIncrease = false,
            ),
        )
        scope.launch {
            val result = runCatching {
                withContext(ioDispatcher) {
                    gateway.perform(request) { writeTransfer ->
                        synchronized(stateLock) {
                            ensureCurrentBeforeSet(request)
                            writeTransfer()
                        }
                    }
                }
            }
            if (!isCurrent(request)) return@launch
            val attachedDevice = gateway.findDevice(request.device.id)
            if (attachedDevice != request.device) {
                refreshDeviceSnapshot()
                return@launch
            }
            if (!runCatching { gateway.hasPermission(request.device) }.getOrDefault(false)) {
                publishSnapshot(
                    snapshot.copy(
                        status = LazerUsbUacVolumeStatus.Failed,
                        currentDb256 = null,
                        muted = false,
                        ranges = emptyList(),
                        canDecrease = false,
                        canIncrease = false,
                        detail = "settings.hifi.usb_uac.transfer_failed",
                    ),
                )
                return@launch
            }
            result.onSuccess(::publishOperation).onFailure {
                if (isCurrent(request)) {
                    publishSnapshot(
                        snapshot.copy(
                            status = LazerUsbUacVolumeStatus.Failed,
                            currentDb256 = null,
                            muted = false,
                            ranges = emptyList(),
                            canDecrease = false,
                            canIncrease = false,
                            detail = "settings.hifi.usb_uac.transfer_failed",
                        ),
                    )
                }
            }
        }
    }

    private fun publishOperation(operation: AndroidUacVolumeOperationResult) {
        when (operation) {
            AndroidUacVolumeOperationResult.Unsupported -> publishSnapshot(
                snapshot.copy(
                    status = LazerUsbUacVolumeStatus.Unsupported,
                    currentDb256 = null,
                    muted = false,
                    ranges = emptyList(),
                    detail = "settings.hifi.usb_uac.unsupported",
                ),
            )

            is AndroidUacVolumeOperationResult.Ready -> {
                val finiteDb256 = (operation.volume as? AndroidUacVolumeValue.Finite)?.db256
                val canDecrease = nextAndroidUacVolumeValue(
                    operation.volume,
                    operation.ranges,
                    AndroidUacVolumeDirection.Down,
                ) != null
                val canIncrease = nextAndroidUacVolumeValue(
                    operation.volume,
                    operation.ranges,
                    AndroidUacVolumeDirection.Up,
                ) != null
                publishSnapshot(
                    snapshot.copy(
                        status = LazerUsbUacVolumeStatus.Ready,
                        currentDb256 = finiteDb256,
                        muted = operation.volume == AndroidUacVolumeValue.Muted,
                        ranges = operation.ranges.map {
                            LazerUsbUacVolumeRange(it.minimumDb256, it.maximumDb256, it.resolutionDb256)
                        },
                        canDecrease = canDecrease,
                        canIncrease = canIncrease,
                        detail = null,
                    ),
                )
            }
        }
    }

    private fun ensureCurrentBeforeSet(request: AndroidUacPermissionRequest) {
        check(isCurrent(request)) { "USB Audio Class request is no longer current" }
        check(gateway.findDevice(request.device.id) == request.device) { "USB device is no longer attached" }
        check(gateway.hasPermission(request.device)) { "USB permission is no longer granted" }
        check(isCurrent(request)) { "USB Audio Class request is no longer current" }
    }

    private fun permissionMatches(
        request: AndroidUacPermissionRequest,
        result: AndroidUacPermissionResult,
    ): Boolean = androidUacPermissionCallbackMatches(
        requestDeviceId = request.device.id,
        requestVendorId = request.device.vendorId,
        requestProductId = request.device.productId,
        requestGeneration = request.generation,
        currentGeneration = generation,
        selectedDeviceId = selectedDeviceId,
        callbackDeviceId = result.device.id,
        callbackVendorId = result.device.vendorId,
        callbackProductId = result.device.productId,
        callbackGeneration = result.generation,
    )

    private fun isCurrent(request: AndroidUacPermissionRequest): Boolean =
        !closed && request.generation == generation && selectedDeviceId == request.device.id

    private fun refreshDeviceSnapshot() {
        if (closed) return
        val devices = runCatching { refreshDevices() }.getOrDefault(snapshot.devices)
        updateDevices(devices)
    }

    private fun invalidateRequests() {
        generation += 1
        pendingRequest = null
    }

    private fun publishSnapshot(value: LazerUsbUacVolumeSnapshot) {
        snapshot = value
        publish(value)
    }
}

/** Runs the descriptor parser and GET/SET requests against one already-open USB connection. */
internal fun performAndroidUacVolumeOperation(
    descriptors: ByteArray,
    transfer: AndroidUsbControlTransfer,
    increase: Boolean?,
    setCurrent: ((transfer: () -> Int) -> Int)? = null,
): AndroidUacVolumeOperationResult {
    val control = findAndroidUacPlaybackVolumeControl(descriptors)
        ?: return AndroidUacVolumeOperationResult.Unsupported
    val volume = AndroidUacHardwareVolume(control, transfer, setCurrent)
    val ranges = volume.readVolumeRanges()
    val current = volume.readCurrentVolume()
    val updated = if (increase == null) {
        current
    } else {
        val direction = if (increase) AndroidUacVolumeDirection.Up else AndroidUacVolumeDirection.Down
        val target = nextAndroidUacVolumeValue(current, ranges, direction)
            ?: return AndroidUacVolumeOperationResult.Ready(current, ranges)
        volume.setAndReadBackDb256(target, ranges)
    }
    return AndroidUacVolumeOperationResult.Ready(updated, ranges)
}
