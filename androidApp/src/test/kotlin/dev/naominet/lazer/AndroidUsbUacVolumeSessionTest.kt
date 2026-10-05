package dev.naominet.lazer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUsbUacVolumeSessionTest {
    @Test
    fun `grant reads descriptor range and current volume then adjustment sends SET and verifies GET`() {
        val gateway = FakeGateway(permissionGranted = false)
        val states = mutableListOf<LazerUsbUacVolumeSnapshot>()
        val session = newSession(gateway, states)
        session.updateDevices(listOf(DEVICE_A))
        session.selectDevice(DEVICE_A.id)

        session.request(increase = null)

        assertEquals(LazerUsbUacVolumeStatus.AwaitingPermission, session.currentSnapshot().status)
        assertTrue(gateway.transfers.requests.isEmpty())
        val readRequest = requireNotNull(gateway.permissionRequests.singleOrNull())
        assertNull(readRequest.increase)
        gateway.permissionGranted = true
        session.onPermissionResult(result(readRequest))

        assertEquals(LazerUsbUacVolumeStatus.Ready, session.currentSnapshot().status)
        assertEquals(-6 * 256, session.currentSnapshot().currentDb256)
        assertEquals(listOf(0x82, 0x83, 0x84, 0x81), gateway.transfers.requests.map { it.request })

        gateway.transfers.requests.clear()
        session.request(increase = true)

        assertEquals(LazerUsbUacVolumeStatus.Ready, session.currentSnapshot().status)
        assertEquals(-5 * 256, session.currentSnapshot().currentDb256)
        assertEquals(listOf(0x82, 0x83, 0x84, 0x81, 0x01, 0x81), gateway.transfers.requests.map { it.request })
        assertEquals(0x21, gateway.transfers.requests[4].requestType)
        assertEquals(-5 * 256, gateway.transfers.setValues.single())
        assertTrue(states.any { it.status == LazerUsbUacVolumeStatus.Reading })
    }

    @Test
    fun `denied permission never opens the device or publishes ready`() {
        val gateway = FakeGateway(permissionGranted = false)
        val session = newSession(gateway)
        session.updateDevices(listOf(DEVICE_A))
        session.selectDevice(DEVICE_A.id)
        session.request(increase = null)
        val request = gateway.permissionRequests.single()

        session.onPermissionResult(result(request, granted = false))
        session.onPermissionResult(result(request, granted = true)) // Duplicate late grant.

        assertEquals(LazerUsbUacVolumeStatus.PermissionDenied, session.currentSnapshot().status)
        assertTrue(gateway.transfers.requests.isEmpty())
    }

    @Test
    fun `detach invalidates pending permission and clears selected device`() {
        val gateway = FakeGateway(permissionGranted = false)
        val session = newSession(gateway)
        session.updateDevices(listOf(DEVICE_A))
        session.selectDevice(DEVICE_A.id)
        session.request(increase = null)
        val request = gateway.permissionRequests.single()
        gateway.devices.remove(DEVICE_A.id)

        session.onDeviceDetached(DEVICE_A.id)
        session.onPermissionResult(result(request))

        assertEquals(LazerUsbUacVolumeStatus.Idle, session.currentSnapshot().status)
        assertNull(session.currentSnapshot().selectedDeviceId)
        assertTrue(gateway.transfers.requests.isEmpty())
    }

    @Test
    fun `missing selected device refreshes the device list before reporting failure`() {
        val gateway = FakeGateway(permissionGranted = true)
        var refreshCount = 0
        val session = newSession(gateway, refreshDevices = {
            refreshCount += 1
            emptyList()
        })
        session.updateDevices(listOf(DEVICE_A))
        session.selectDevice(DEVICE_A.id)
        gateway.devices.remove(DEVICE_A.id)

        session.request(increase = null)

        assertEquals(1, refreshCount)
        assertNull(session.currentSnapshot().selectedDeviceId)
        assertEquals(LazerUsbUacVolumeStatus.Failed, session.currentSnapshot().status)
        assertTrue(gateway.transfers.requests.isEmpty())
    }

    @Test
    fun `device removal during read refreshes devices and drops the late result`() {
        val gateway = FakeGateway(permissionGranted = true)
        val states = mutableListOf<LazerUsbUacVolumeSnapshot>()
        var refreshCount = 0
        val session = newSession(gateway, states) {
            refreshCount += 1
            emptyList()
        }
        session.updateDevices(listOf(DEVICE_A))
        session.selectDevice(DEVICE_A.id)
        gateway.transfers.onCurrentRead = { gateway.devices.remove(DEVICE_A.id) }

        session.request(increase = null)

        assertEquals(1, refreshCount)
        assertNull(session.currentSnapshot().selectedDeviceId)
        assertEquals(LazerUsbUacVolumeStatus.Idle, session.currentSnapshot().status)
        assertFalse(states.any { it.status == LazerUsbUacVolumeStatus.Ready })
    }

    @Test
    fun `changing selection rejects old permission callback`() {
        val gateway = FakeGateway(permissionGranted = false)
        val session = newSession(gateway)
        session.updateDevices(listOf(DEVICE_A, DEVICE_B))
        session.selectDevice(DEVICE_A.id)
        session.request(increase = null)
        val requestA = gateway.permissionRequests.single()

        session.selectDevice(DEVICE_B.id)
        gateway.permissionGranted = true
        session.onPermissionResult(result(requestA))

        assertEquals(DEVICE_B.id, session.currentSnapshot().selectedDeviceId)
        assertEquals(LazerUsbUacVolumeStatus.Idle, session.currentSnapshot().status)
        assertTrue(gateway.transfers.requests.isEmpty())
        assertTrue(gateway.permissionRequests.size == 1)
    }

    @Test
    fun `close rejects late permission callback and future requests`() {
        val gateway = FakeGateway(permissionGranted = false)
        val session = newSession(gateway)
        session.updateDevices(listOf(DEVICE_A))
        session.selectDevice(DEVICE_A.id)
        session.request(increase = null)
        val request = gateway.permissionRequests.single()

        session.close()
        gateway.permissionGranted = true
        session.onPermissionResult(result(request))
        session.request(increase = null)

        assertEquals(LazerUsbUacVolumeStatus.AwaitingPermission, session.currentSnapshot().status)
        assertEquals(1, gateway.permissionRequests.size)
        assertTrue(gateway.transfers.requests.isEmpty())
    }

    @Test
    fun `close while UAC read is in flight ignores late completion`() = runBlocking {
        val gateway = FakeGateway(permissionGranted = true)
        val states = mutableListOf<LazerUsbUacVolumeSnapshot>()
        val operationStarted = CompletableDeferred<Unit>()
        val finishOperation = CompletableDeferred<AndroidUacVolumeOperationResult>()
        gateway.performOverride = {
            operationStarted.complete(Unit)
            finishOperation.await()
        }
        val session = newSession(gateway, states)
        session.updateDevices(listOf(DEVICE_A))
        session.selectDevice(DEVICE_A.id)

        session.request(increase = null)
        withTimeout(1_000L) { operationStarted.await() }
        assertEquals(LazerUsbUacVolumeStatus.Reading, session.currentSnapshot().status)

        session.close()
        finishOperation.complete(
            AndroidUacVolumeOperationResult.Ready(
                AndroidUacVolumeValue.Finite(-6 * 256),
                listOf(AndroidUacVolumeRange(-12 * 256, 0, 256)),
            ),
        )

        assertFalse(states.any { it.status == LazerUsbUacVolumeStatus.Ready })
        assertEquals(LazerUsbUacVolumeStatus.Reading, session.currentSnapshot().status)
    }

    @Test
    fun `selection change between current read and SET prevents stale hardware write`() {
        val gateway = FakeGateway(permissionGranted = true)
        val session = newSession(gateway)
        session.updateDevices(listOf(DEVICE_A, DEVICE_B))
        session.selectDevice(DEVICE_A.id)
        gateway.transfers.onCurrentRead = { session.selectDevice(DEVICE_B.id) }

        session.request(increase = true)

        assertEquals(DEVICE_B.id, session.currentSnapshot().selectedDeviceId)
        assertEquals(LazerUsbUacVolumeStatus.Idle, session.currentSnapshot().status)
        assertFalse(gateway.transfers.requests.any { it.requestType == 0x21 })
        assertTrue(gateway.transfers.setValues.isEmpty())
    }

    @Test
    fun `detach between current read and SET prevents stale hardware write`() {
        val gateway = FakeGateway(permissionGranted = true)
        val session = newSession(gateway)
        session.updateDevices(listOf(DEVICE_A))
        session.selectDevice(DEVICE_A.id)
        gateway.transfers.onCurrentRead = {
            gateway.devices.remove(DEVICE_A.id)
            session.onDeviceDetached(DEVICE_A.id)
        }

        session.request(increase = true)

        assertNull(session.currentSnapshot().selectedDeviceId)
        assertEquals(LazerUsbUacVolumeStatus.Idle, session.currentSnapshot().status)
        assertFalse(gateway.transfers.requests.any { it.requestType == 0x21 })
        assertTrue(gateway.transfers.setValues.isEmpty())
    }

    @Test
    fun `close between current read and SET prevents stale hardware write`() {
        val gateway = FakeGateway(permissionGranted = true)
        val states = mutableListOf<LazerUsbUacVolumeSnapshot>()
        val session = newSession(gateway, states)
        session.updateDevices(listOf(DEVICE_A))
        session.selectDevice(DEVICE_A.id)
        gateway.transfers.onCurrentRead = { session.close() }

        session.request(increase = true)

        assertEquals(LazerUsbUacVolumeStatus.Reading, session.currentSnapshot().status)
        assertFalse(gateway.transfers.requests.any { it.requestType == 0x21 })
        assertTrue(gateway.transfers.setValues.isEmpty())
        assertFalse(states.any { it.status == LazerUsbUacVolumeStatus.Ready })
    }

    @Test
    fun `selection waits for an already linearized SET_CUR write`() {
        val gateway = FakeGateway(permissionGranted = true)
        val session = newSession(gateway)
        session.updateDevices(listOf(DEVICE_A, DEVICE_B))
        session.selectDevice(DEVICE_A.id)

        val selectionStarted = CountDownLatch(1)
        val selectionCompleted = CountDownLatch(1)
        val selectionFailure = AtomicReference<Throwable?>()
        val selectionThread = Thread {
            selectionStarted.countDown()
            try {
                session.selectDevice(DEVICE_B.id)
            } catch (failure: Throwable) {
                selectionFailure.set(failure)
            } finally {
                selectionCompleted.countDown()
            }
        }
        gateway.transfers.onSet = {
            selectionThread.start()
            assertTrue(selectionStarted.await(1, TimeUnit.SECONDS))
            val blockedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (selectionThread.state != Thread.State.BLOCKED &&
                selectionCompleted.count != 0L && System.nanoTime() < blockedDeadline
            ) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, selectionThread.state)
        }

        session.request(increase = true)
        assertTrue(selectionCompleted.await(1, TimeUnit.SECONDS))
        selectionThread.join(1_000L)

        assertNull(selectionFailure.get())
        assertEquals(1, gateway.transfers.setValues.size)
        assertEquals(DEVICE_B.id, session.currentSnapshot().selectedDeviceId)
    }

    @Test
    fun `control transfer failure publishes failure but never ready`() {
        val gateway = FakeGateway(permissionGranted = true).apply { transfers.setResult = -1 }
        val states = mutableListOf<LazerUsbUacVolumeSnapshot>()
        val session = newSession(gateway, states)
        session.updateDevices(listOf(DEVICE_A))
        session.selectDevice(DEVICE_A.id)

        session.request(increase = true)

        assertEquals(LazerUsbUacVolumeStatus.Failed, session.currentSnapshot().status)
        assertFalse(states.any { it.status == LazerUsbUacVolumeStatus.Ready })
        assertTrue(gateway.transfers.requests.any { it.requestType == 0x21 })
    }

    private fun newSession(
        gateway: FakeGateway,
        states: MutableList<LazerUsbUacVolumeSnapshot> = mutableListOf(),
        refreshDevices: () -> List<LazerUsbUacDeviceOption> = { emptyList() },
    ) = AndroidUsbUacVolumeSession(
        gateway = gateway,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        ioDispatcher = Dispatchers.Unconfined,
        refreshDevices = refreshDevices,
        publish = states::add,
    )

    private fun result(
        request: AndroidUacPermissionRequest,
        granted: Boolean = true,
        generation: Long? = request.generation,
        device: AndroidUacDeviceIdentity = request.device,
    ) = AndroidUacPermissionResult(device, generation, granted)

    private inner class FakeGateway(permissionGranted: Boolean) : AndroidUacVolumeGateway {
        val devices = mutableMapOf(DEVICE_A.id to DEVICE_A.identity(), DEVICE_B.id to DEVICE_B.identity())
        val permissionRequests = mutableListOf<AndroidUacPermissionRequest>()
        val transfers = FakeTransfer()
        var permissionGranted: Boolean = permissionGranted
        var performOverride: (suspend () -> AndroidUacVolumeOperationResult)? = null

        override fun findDevice(deviceId: String): AndroidUacDeviceIdentity? = devices[deviceId]

        override fun hasPermission(device: AndroidUacDeviceIdentity): Boolean =
            permissionGranted && devices[device.id] == device

        override fun requestPermission(request: AndroidUacPermissionRequest) {
            permissionRequests += request
        }

        override suspend fun perform(
            request: AndroidUacPermissionRequest,
            setCurrent: (transfer: () -> Int) -> Int,
        ): AndroidUacVolumeOperationResult {
            performOverride?.let { return it() }
            check(hasPermission(request.device))
            return performAndroidUacVolumeOperation(
                descriptors = uac1Descriptors(),
                transfer = transfers,
                increase = request.increase,
                setCurrent = setCurrent,
            )
        }
    }

    private inner class FakeTransfer : AndroidUsbControlTransfer {
        val requests = mutableListOf<AndroidUsbControlRequest>()
        val setValues = mutableListOf<Int>()
        var currentDb256 = -6 * 256
        var setResult = 2
        var onCurrentRead: (() -> Unit)? = null
        var onSet: (() -> Unit)? = null
        private var currentReadCount = 0

        override fun transfer(request: AndroidUsbControlRequest, timeoutMillis: Int): Int {
            requests += request.copy(data = request.data.copyOf())
            if (request.requestType == 0x21) {
                onSet?.invoke()
                val value = ((request.data[1].toInt() and 0xff) shl 8 or
                    (request.data[0].toInt() and 0xff)).toShort().toInt()
                setValues += value
                if (setResult == request.length) currentDb256 = value
                return setResult
            }
            when (request.request) {
                0x82 -> writeSigned16(request.data, -12 * 256)
                0x83 -> writeSigned16(request.data, 0)
                0x84 -> writeSigned16(request.data, 256)
                0x81 -> {
                    currentReadCount += 1
                    onCurrentRead?.takeIf { currentReadCount == 1 }?.invoke()
                    writeSigned16(request.data, currentDb256)
                }
            }
            return request.length
        }
    }

    private fun uac1Descriptors(): ByteArray {
        val inputTerminal = byteArrayOf(12, 0x24, 0x02, 1, 0x01, 0x01, 0, 1, 0, 0, 0, 0)
        // UAC1 Feature Unit master bitmap advertises Volume Control; channel bitmap is unused.
        val featureUnit = byteArrayOf(9, 0x24, 0x06, 5, 1, 1, 0x02, 0, 0)
        val outputTerminal = byteArrayOf(9, 0x24, 0x03, 7, 0x02, 0x03, 0, 5, 0)
        val entities = inputTerminal + featureUnit + outputTerminal
        val controlTotalLength = 9 + entities.size
        val interfaceDescriptor = byteArrayOf(9, 4, 1, 0, 0, 1, 1, 0, 0)
        val header = byteArrayOf(9, 0x24, 1, 0, 1, controlTotalLength.toByte(), 0, 1, 2)
        val body = interfaceDescriptor + header + entities
        val totalLength = 9 + body.size
        return byteArrayOf(9, 2, totalLength.toByte(), (totalLength ushr 8).toByte(), 1, 1, 0, 0x80.toByte(), 50) + body
    }

    private fun writeSigned16(bytes: ByteArray, value: Int) {
        bytes[0] = value.toByte()
        bytes[1] = (value ushr 8).toByte()
    }

    private companion object {
        val DEVICE_A = LazerUsbUacDeviceOption("/dev/bus/usb/001/002", "DAC A", 0x1234, 0xabcd)
        val DEVICE_B = LazerUsbUacDeviceOption("/dev/bus/usb/001/003", "DAC B", 0x4321, 0xdcba)
    }

    private fun LazerUsbUacDeviceOption.identity() = AndroidUacDeviceIdentity(id, vendorId, productId)
}
