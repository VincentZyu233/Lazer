package dev.naominet.lazer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUsbUacDirectOutputSessionTest {
    @Test
    fun `request waits for permission and enables only after matching granted result`() {
        val harness = Harness()

        harness.session.enable(DEVICE_A, hasPermission = false)

        val request = harness.requests.single()
        assertEquals(LazerUsbUacDirectOutputStatus.AwaitingPermission, harness.snapshot.status)
        assertFalse(harness.snapshot.enabled)
        assertTrue(harness.outputCalls.isEmpty())

        harness.grant(request)

        assertEquals(LazerUsbUacDirectOutputSnapshot(true, DEVICE_A.id, LazerUsbUacDirectOutputStatus.Enabled), harness.snapshot)
        assertEquals(listOf(true to DEVICE_A.id), harness.outputCalls)
    }

    @Test
    fun `permission denial leaves direct output disabled`() {
        val harness = Harness()
        harness.session.enable(DEVICE_A, hasPermission = false)
        val request = harness.requests.single()

        harness.result(request, granted = false)

        assertEquals(LazerUsbUacDirectOutputStatus.PermissionDenied, harness.snapshot.status)
        assertFalse(harness.snapshot.enabled)
        assertTrue(harness.outputCalls.isEmpty())
    }

    @Test
    fun `old permission broadcast cannot complete a newer request`() {
        val harness = Harness()
        harness.session.enable(DEVICE_A, hasPermission = false)
        val old = harness.requests.single()
        harness.session.disable(DEVICE_A.id)
        harness.session.enable(DEVICE_A, hasPermission = false)
        val current = harness.requests.last()

        harness.grant(old)

        assertEquals(LazerUsbUacDirectOutputStatus.AwaitingPermission, harness.snapshot.status)
        assertFalse(harness.snapshot.enabled)
        assertTrue(harness.outputCalls.none { it.first })

        harness.grant(current)
        assertTrue(harness.snapshot.enabled)
    }

    @Test
    fun `grant after disabling pending request stays off`() {
        val harness = Harness()
        harness.session.enable(DEVICE_A, hasPermission = false)
        val request = harness.requests.single()

        harness.session.disable(DEVICE_A.id)
        harness.grant(request)

        assertEquals(LazerUsbUacDirectOutputSnapshot(), harness.snapshot)
        assertFalse(harness.outputCalls.any { it.first })
        assertEquals(listOf(false to DEVICE_A.id), harness.outputCalls)
    }

    @Test
    fun `permission result for changed or detached device fails closed`() {
        val harness = Harness()
        harness.session.enable(DEVICE_A, hasPermission = false)
        val request = harness.requests.single()

        harness.session.onPermissionResult(
            result = harness.permissionResult(request),
            selectedDevice = DEVICE_B,
            attachedDevice = DEVICE_A,
            hasPermission = true,
        )

        assertEquals(LazerUsbUacDirectOutputStatus.Failed, harness.snapshot.status)
        assertEquals("settings.hifi.usb_uac.direct_output.device_changed", harness.snapshot.detail)
        assertFalse(harness.snapshot.enabled)
        assertTrue(harness.outputCalls.isEmpty())
    }

    @Test
    fun `stale callback from different request id is ignored`() {
        val harness = Harness()
        harness.session.enable(DEVICE_A, hasPermission = false)
        val request = harness.requests.single()

        harness.session.onPermissionResult(
            result = harness.permissionResult(request).copy(requestId = request.requestId + 100),
            selectedDevice = DEVICE_A,
            attachedDevice = DEVICE_A,
            hasPermission = true,
        )

        assertEquals(LazerUsbUacDirectOutputStatus.AwaitingPermission, harness.snapshot.status)
        assertFalse(harness.snapshot.enabled)
        assertTrue(harness.outputCalls.isEmpty())
    }

    @Test
    fun `changing selection and detaching enabled device both disable it`() {
        val harness = Harness()
        harness.session.enable(DEVICE_A, hasPermission = true)
        assertTrue(harness.snapshot.enabled)

        harness.session.onSelectedDeviceChanged(DEVICE_B.id)

        assertFalse(harness.snapshot.enabled)
        assertNull(harness.snapshot.deviceId)
        assertEquals(listOf(true to DEVICE_A.id, false to DEVICE_A.id), harness.outputCalls)

        harness.session.enable(DEVICE_A, hasPermission = true)
        harness.session.onDeviceDetached(DEVICE_A.id)
        assertFalse(harness.snapshot.enabled)
        assertEquals(listOf(true to DEVICE_A.id, false to DEVICE_A.id, true to DEVICE_A.id, false to DEVICE_A.id), harness.outputCalls)
    }

    @Test
    fun `permission request and service errors publish failed disabled state`() {
        val permissionHarness = Harness(requestPermission = { error("request failure") })
        permissionHarness.session.enable(DEVICE_A, hasPermission = false)
        assertEquals(LazerUsbUacDirectOutputStatus.Failed, permissionHarness.snapshot.status)
        assertEquals("settings.hifi.usb_uac.direct_output.request_failed", permissionHarness.snapshot.detail)
        assertFalse(permissionHarness.snapshot.enabled)

        val outputHarness = Harness(setOutput = { enabled, _ -> if (enabled) error("service failure") })
        outputHarness.session.enable(DEVICE_A, hasPermission = true)
        assertEquals(LazerUsbUacDirectOutputStatus.Failed, outputHarness.snapshot.status)
        assertEquals("settings.hifi.usb_uac.direct_output.enable_failed", outputHarness.snapshot.detail)
        assertFalse(outputHarness.snapshot.enabled)
        assertEquals(listOf(true to DEVICE_A.id, false to DEVICE_A.id), outputHarness.outputCalls)
    }

    @Test
    fun `enabling without selected device never requests permission or enables output`() {
        val harness = Harness()

        harness.session.enable(selectedDevice = null, hasPermission = false)

        assertEquals(LazerUsbUacDirectOutputStatus.Failed, harness.snapshot.status)
        assertEquals("settings.hifi.usb_uac.direct_output.no_device", harness.snapshot.detail)
        assertTrue(harness.requests.isEmpty())
        assertTrue(harness.outputCalls.isEmpty())
    }

    private class Harness(
        requestPermission: ((AndroidUsbUacDirectOutputRequest) -> Unit)? = null,
        setOutput: ((enabled: Boolean, deviceId: String) -> Unit)? = null,
    ) {
        val requests = mutableListOf<AndroidUsbUacDirectOutputRequest>()
        val outputCalls = mutableListOf<Pair<Boolean, String>>()
        var snapshot = LazerUsbUacDirectOutputSnapshot()
        val session = AndroidUsbUacDirectOutputSession(
            requestPermission = { request ->
                requests += request
                requestPermission?.invoke(request)
            },
            setOutput = { enabled, id ->
                outputCalls += enabled to id
                setOutput?.invoke(enabled, id)
            },
            publish = { snapshot = it },
        )

        fun permissionResult(request: AndroidUsbUacDirectOutputRequest) =
            AndroidUsbUacDirectOutputPermissionResult(
                requestId = request.requestId,
                deviceId = request.deviceId,
                vendorId = request.vendorId,
                productId = request.productId,
                granted = true,
            )

        fun result(request: AndroidUsbUacDirectOutputRequest, granted: Boolean) {
            session.onPermissionResult(
                result = permissionResult(request).copy(granted = granted),
                selectedDevice = DEVICE_A,
                attachedDevice = DEVICE_A,
                hasPermission = granted,
            )
        }

        fun grant(request: AndroidUsbUacDirectOutputRequest) = result(request, granted = true)
    }

    private companion object {
        val DEVICE_A = LazerUsbUacDeviceOption("usb-a", "DAC A", 0x1234, 0x0100)
        val DEVICE_B = LazerUsbUacDeviceOption("usb-b", "DAC B", 0x1234, 0x0101)
    }
}
