package dev.naominet.lazer

/** Correlation data for one independently requested Android USB permission prompt. */
internal data class AndroidUsbUacDirectOutputRequest(
    val requestId: Long,
    val deviceId: String,
    val vendorId: Int,
    val productId: Int,
)

internal data class AndroidUsbUacDirectOutputPermissionResult(
    val requestId: Long?,
    val deviceId: String,
    val vendorId: Int,
    val productId: Int,
    val granted: Boolean,
)

/**
 * Platform-independent permission and intent state for explicit Android UAC PCM output.
 * Permission is only one gate: the callback must match the current request, attached device, and
 * the device currently selected in the existing USB UAC picker before output can be enabled.
 */
internal class AndroidUsbUacDirectOutputSession(
    initialSnapshot: LazerUsbUacDirectOutputSnapshot = LazerUsbUacDirectOutputSnapshot(),
    private val requestPermission: (AndroidUsbUacDirectOutputRequest) -> Unit,
    private val setOutput: (enabled: Boolean, deviceId: String) -> Unit,
    private val publish: (LazerUsbUacDirectOutputSnapshot) -> Unit,
) {
    private var nextRequestId = 0L
    private var pendingRequest: AndroidUsbUacDirectOutputRequest? = null
    private var enabledDeviceId: String? = initialSnapshot.deviceId.takeIf { initialSnapshot.enabled }
    private var snapshot = initialSnapshot

    fun currentSnapshot(): LazerUsbUacDirectOutputSnapshot = snapshot

    fun enable(selectedDevice: LazerUsbUacDeviceOption?, hasPermission: Boolean) {
        reconcilePublishedPlaybackState()
        if (selectedDevice == null) {
            if (!cancelCurrent()) return
            publishSnapshot(
                LazerUsbUacDirectOutputSnapshot(
                    status = LazerUsbUacDirectOutputStatus.Failed,
                    detail = "settings.hifi.usb_uac.direct_output.no_device",
                ),
            )
            return
        }
        if (snapshot.enabled && enabledDeviceId == selectedDevice.id) return

        if (!cancelCurrent()) return
        nextRequestId = if (nextRequestId == Long.MAX_VALUE) 1L else nextRequestId + 1L
        val request = AndroidUsbUacDirectOutputRequest(
            requestId = nextRequestId,
            deviceId = selectedDevice.id,
            vendorId = selectedDevice.vendorId,
            productId = selectedDevice.productId,
        )
        pendingRequest = request
        if (hasPermission) {
            activate(request)
            return
        }

        publishSnapshot(
            LazerUsbUacDirectOutputSnapshot(
                deviceId = selectedDevice.id,
                status = LazerUsbUacDirectOutputStatus.AwaitingPermission,
            ),
        )
        try {
            requestPermission(request)
        } catch (_: Throwable) {
            failPending(request, "settings.hifi.usb_uac.direct_output.request_failed")
        }
    }

    fun onPermissionResult(
        result: AndroidUsbUacDirectOutputPermissionResult,
        selectedDevice: LazerUsbUacDeviceOption?,
        attachedDevice: LazerUsbUacDeviceOption?,
        hasPermission: Boolean,
    ) {
        val request = pendingRequest ?: return
        if (result.requestId != request.requestId) return

        val callbackMatchesRequest = result.deviceId == request.deviceId &&
            result.vendorId == request.vendorId && result.productId == request.productId
        if (!callbackMatchesRequest) {
            failPending(request, "settings.hifi.usb_uac.direct_output.device_changed")
            return
        }

        val selectedMatchesRequest = selectedDevice.matches(request)
        val attachedMatchesRequest = attachedDevice.matches(request)
        if (!selectedMatchesRequest || !attachedMatchesRequest) {
            failPending(request, "settings.hifi.usb_uac.direct_output.device_changed")
            return
        }

        if (!result.granted || !hasPermission) {
            pendingRequest = null
            publishSnapshot(
                LazerUsbUacDirectOutputSnapshot(
                    deviceId = request.deviceId,
                    status = LazerUsbUacDirectOutputStatus.PermissionDenied,
                    detail = "settings.hifi.usb_uac.direct_output.permission_denied",
                ),
            )
            return
        }
        activate(request)
    }

    fun disable(selectedDeviceId: String?) {
        val target = enabledDeviceId ?: pendingRequest?.deviceId ?: snapshot.deviceId ?: selectedDeviceId
        pendingRequest = null
        enabledDeviceId = null
        if (target == null) {
            publishSnapshot(LazerUsbUacDirectOutputSnapshot())
            return
        }

        publishSnapshot(LazerUsbUacDirectOutputSnapshot())
        try {
            setOutput(false, target)
        } catch (_: Throwable) {
            publishSnapshot(
                LazerUsbUacDirectOutputSnapshot(
                    status = LazerUsbUacDirectOutputStatus.Failed,
                    detail = "settings.hifi.usb_uac.direct_output.disable_failed",
                ),
            )
        }
    }

    fun onSelectedDeviceChanged(selectedDeviceId: String?) {
        val currentDeviceId = enabledDeviceId ?: pendingRequest?.deviceId
        if (currentDeviceId != null && currentDeviceId != selectedDeviceId) disable(currentDeviceId)
    }

    fun onDeviceDetached(deviceId: String) {
        val currentDeviceId = enabledDeviceId ?: pendingRequest?.deviceId
        if (currentDeviceId == deviceId) disable(deviceId)
    }

    private fun activate(request: AndroidUsbUacDirectOutputRequest) {
        if (pendingRequest != request) return
        try {
            setOutput(true, request.deviceId)
            pendingRequest = null
            enabledDeviceId = request.deviceId
            publishSnapshot(
                LazerUsbUacDirectOutputSnapshot(
                    enabled = true,
                    deviceId = request.deviceId,
                    status = LazerUsbUacDirectOutputStatus.Enabled,
                ),
            )
        } catch (_: Throwable) {
            runCatching { setOutput(false, request.deviceId) }
            failPending(request, "settings.hifi.usb_uac.direct_output.enable_failed")
        }
    }

    private fun failPending(request: AndroidUsbUacDirectOutputRequest, detail: String) {
        if (pendingRequest != request) return
        pendingRequest = null
        enabledDeviceId = null
        publishSnapshot(
            LazerUsbUacDirectOutputSnapshot(
                deviceId = request.deviceId,
                status = LazerUsbUacDirectOutputStatus.Failed,
                detail = detail,
            ),
        )
    }

    private fun cancelCurrent(): Boolean {
        val oldDeviceId = enabledDeviceId ?: pendingRequest?.deviceId
        pendingRequest = null
        enabledDeviceId = null
        if (oldDeviceId == null) return true
        publishSnapshot(LazerUsbUacDirectOutputSnapshot())
        return try {
            setOutput(false, oldDeviceId)
            true
        } catch (_: Throwable) {
            publishSnapshot(
                LazerUsbUacDirectOutputSnapshot(
                    status = LazerUsbUacDirectOutputStatus.Failed,
                    detail = "settings.hifi.usb_uac.direct_output.disable_failed",
                ),
            )
            false
        }
    }

    private fun publishSnapshot(value: LazerUsbUacDirectOutputSnapshot) {
        snapshot = value
        publish(value)
    }

    private fun reconcilePublishedPlaybackState() {
        val published = LazerUsbUacDirectOutputStateStore.state.value
        if (published.deviceId == snapshot.deviceId && published.status != LazerUsbUacDirectOutputStatus.Enabled) {
            snapshot = published
            if (!published.enabled) enabledDeviceId = null
        }
    }

    private fun LazerUsbUacDeviceOption?.matches(request: AndroidUsbUacDirectOutputRequest): Boolean =
        this != null && id == request.deviceId && vendorId == request.vendorId && productId == request.productId
}
