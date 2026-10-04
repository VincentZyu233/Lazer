package dev.naominet.lazer

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.BackEventCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android's answers to the screen-level asks of the shared interface. The registry callbacks are
 * registered once for the activity's life and hand their result to whichever request is waiting,
 * so the shared sheets never learn that a launcher exists.
 */
class AndroidScreenHost(private val activity: ComponentActivity) : LazerScreenHost {
    private val audioManager = activity.getSystemService(AudioManager::class.java)
    private val usbManager = activity.getSystemService(UsbManager::class.java)
    private val usbOutputTargetStore = AndroidUsbAudioTargetStore(activity)
    private val mutableUsbAudioTargetSelection = MutableStateFlow(LazerUsbAudioTargetSnapshot())
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshAudioOutputDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshAudioOutputDevices()
    }
    private val mutableUsbUacVolume = MutableStateFlow(LazerUsbUacVolumeSnapshot())
    private var usbUacGeneration = 0L
    private var pendingUsbUacRequest: PendingUsbUacRequest? = null
    private val usbUacReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDeviceExtra() ?: return
                    if (mutableUsbUacVolume.value.selectedDeviceId == device.deviceName) {
                        usbUacGeneration += 1
                        pendingUsbUacRequest = null
                        publishUsbUacVolume(
                            mutableUsbUacVolume.value.copy(
                                devices = mutableUsbUacVolume.value.devices.filterNot { it.id == device.deviceName },
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
                    refreshUsbUacDevices()
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshUsbUacDevices()

                ACTION_USB_UAC_PERMISSION -> onUsbUacPermissionResult(intent)
            }
        }
    }
    private var usbUacReceiverRegistered = false
    private val registry = activity.activityResultRegistry
    private val imageLauncher = registry.register(KEY_BACKGROUND, ActivityResultContracts.GetContent()) { uri ->
        pendingImage?.invoke(uri?.toString())
        pendingImage = null
    }
    private val localAudioLauncher = registry.register(
        KEY_LOCAL_AUDIO,
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val callback = pendingLocalAudio
        pendingLocalAudio = null
        uris.forEach { uri ->
            runCatching {
                activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        activity.lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> runCatching { readAndroidLocalAudioFile(activity, uri) }.getOrNull() }
            }
            callback?.invoke(LazerLocalAudioPickerResult(files, (uris.size - files.size).coerceAtLeast(0)))
        }
    }
    private val exportLauncher = registry.register(KEY_EXPORT, ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        pendingTarget?.invoke(uri?.toString())
        pendingTarget = null
    }
    private val scanLauncher = registry.register(KEY_SCAN, ScanContract()) { result ->
        pendingScan?.invoke(result.contents)
        pendingScan = null
    }
    private val microphoneLauncher = registry.register(KEY_MICROPHONE, ActivityResultContracts.RequestPermission()) { granted ->
        pendingMicrophone?.invoke(granted)
        pendingMicrophone = null
    }
    private var pendingImage: ((String?) -> Unit)? = null
    private var pendingLocalAudio: ((LazerLocalAudioPickerResult) -> Unit)? = null
    private var pendingTarget: ((String?) -> Unit)? = null
    private var pendingScan: ((String?) -> Unit)? = null
    private var pendingMicrophone: ((Boolean) -> Unit)? = null

    init {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        registerUsbUacReceiver()
        refreshAudioOutputDevices()
        refreshUsbUacDevices()
    }

    override val supportsSystemPalette: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    override fun dynamicColorScheme(isDark: Boolean): ColorScheme? =
        if (supportsSystemPalette) {
            if (isDark) dynamicDarkColorScheme(activity) else dynamicLightColorScheme(activity)
        } else {
            null
        }

    // Insets only exist once the window has been laid out; the caller recomposes with the window.
    override val screenCornerRadiusPx: Float
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val insets = activity.window.decorView.rootWindowInsets
            intArrayOf(
                android.view.RoundedCorner.POSITION_TOP_LEFT,
                android.view.RoundedCorner.POSITION_TOP_RIGHT,
                android.view.RoundedCorner.POSITION_BOTTOM_LEFT,
                android.view.RoundedCorner.POSITION_BOTTOM_RIGHT,
            ).maxOfOrNull { position -> insets?.getRoundedCorner(position)?.radius?.toFloat() ?: 0f }
                ?: 0f
        } else {
            0f
        }

    @Suppress("DEPRECATION")
    override val systemHapticsEnabled: Boolean
        get() = Settings.System.getInt(
            activity.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1,
        ) != 0

    override val isDebugBuild: Boolean
        get() = (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override val deviceLabel: String get() = Build.MODEL.orEmpty()

    override val deviceFingerprint: String get() = Build.FINGERPRINT.orEmpty()

    override val supportsAudioSpectrum: Boolean get() = true

    override val usesSystemAudioFocus: Boolean get() = true

    override val supportsPcmTestTone: Boolean get() = true

    override val supportsLocalAudioFiles: Boolean get() = true

    override val supportsUsbAudioTargetSelection: Boolean get() = true

    override val supportsUsbUacVolumeDiagnostics: Boolean get() = true

    override val usbAudioTargetSelection get() = mutableUsbAudioTargetSelection

    override val usbUacVolumeState get() = mutableUsbUacVolume

    override val pcmTestToneState get() = LazerPcmTestToneStateStore.state

    override fun playPcmTestTone(format: LazerPcmTestFormat) = AndroidPlaybackConnection.playPcmTestTone(activity, format)

    override fun stopPcmTestTone() = AndroidPlaybackConnection.stopPcmTestTone(activity)

    override fun selectUsbAudioTarget(identity: String?) {
        if (usbOutputTargetStore.selectedIdentity == identity) return
        val label = identity?.let { id ->
            mutableUsbAudioTargetSelection.value.connectedTargets.singleOrNull { it.id == id }?.label
        }
        if (identity != null && label == null) return
        usbOutputTargetStore.select(identity, label)
        AndroidPlaybackConnection.updateUsbAudioTarget(activity, identity)
        refreshUsbAudioTargets()
    }

    override fun refreshUsbUacDevices() {
        val devices = runCatching {
            usbManager.deviceList.values
                .filter(::hasUsbAudioControlInterface)
                .map { device ->
                    val name = runCatching { device.productName?.trim().orEmpty() }
                        .getOrNull().orEmpty().ifBlank { "USB audio device" }
                    val usbId = "${device.vendorId.toString(16).padStart(4, '0')}:" +
                        device.productId.toString(16).padStart(4, '0')
                    LazerUsbUacDeviceOption(
                        id = device.deviceName,
                        label = "$name · $usbId",
                        vendorId = device.vendorId,
                        productId = device.productId,
                    )
                }
                .sortedBy(LazerUsbUacDeviceOption::label)
        }.getOrDefault(emptyList())
        val state = mutableUsbUacVolume.value
        val selectedStillConnected = state.selectedDeviceId?.let { selectedId ->
            devices.singleOrNull { it.id == selectedId }
        }
        if (state.selectedDeviceId != null && selectedStillConnected == null) {
            usbUacGeneration += 1
            pendingUsbUacRequest = null
        }
        publishUsbUacVolume(
            if (selectedStillConnected == null && state.selectedDeviceId != null) {
                state.copy(
                    devices = devices,
                    selectedDeviceId = null,
                    status = LazerUsbUacVolumeStatus.Idle,
                    currentDb256 = null,
                    muted = false,
                    ranges = emptyList(),
                    canDecrease = false,
                    canIncrease = false,
                    detail = "settings.hifi.usb_uac.device_removed",
                )
            } else {
                state.copy(devices = devices)
            },
        )
    }

    override fun selectUsbUacDevice(identity: String?) {
        val devices = mutableUsbUacVolume.value.devices
        if (identity != null && devices.none { it.id == identity }) return
        usbUacGeneration += 1
        pendingUsbUacRequest = null
        publishUsbUacVolume(
            LazerUsbUacVolumeSnapshot(
                devices = devices,
                selectedDeviceId = identity,
            ),
        )
    }

    override fun readUsbUacVolume() {
        requestUsbUacOperation(increase = null)
    }

    override fun adjustUsbUacVolume(increase: Boolean) {
        requestUsbUacOperation(increase)
    }

    fun refreshPcmTestToneCapabilities() {
        val queried = runCatching { AndroidPcmTestToneOutput.queryCapabilities(activity) }.getOrNull() ?: return
        LazerPcmTestToneStateStore.publish(
            LazerPcmTestToneStateStore.state.value.copy(
                availableFormats = queried.availableFormats,
                connectedUsbOutputs = queried.connectedUsbOutputs,
            ),
        )
    }

    private fun refreshAudioOutputDevices() {
        refreshPcmTestToneCapabilities()
        refreshUsbAudioTargets()
        refreshUsbUacDevices()
        // Re-evaluate a saved target after hotplug even when Android keeps the current AudioTrack
        // on its existing default route and therefore emits no routing-changed callback.
        AndroidPlaybackConnection.refreshUsbAudioTarget(activity, usbOutputTargetStore.selectedIdentity)
    }

    private fun refreshUsbAudioTargets() {
        val deviceTargets = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                .mapNotNull { device ->
                    val label = device.productName.toString().trim().ifBlank { "USB audio output" }
                    stableUsbAudioTargetIdentity(device.type, usbAudioAddressOrEmpty(device), label)
                        ?.let { LazerUsbAudioTargetOption(it, label) }
                }
        }.getOrDefault(emptyList())
        val targetsByIdentity = deviceTargets.groupBy(LazerUsbAudioTargetOption::id)
        // Do not offer a target when its durable identity is shared by multiple attached devices.
        // The chooser policy also fails closed if such an identity was saved before reconnect.
        val targets = targetsByIdentity.values
            .filter { it.size == 1 }
            .map { it.single() }
            .sortedBy(LazerUsbAudioTargetOption::label)
        val selectedId = usbOutputTargetStore.selectedIdentity
        val selectedConnected = selectedId?.let { id -> targets.singleOrNull { it.id == id } }
        val selectedAmbiguous = selectedId?.let { id -> targetsByIdentity[id]?.size?.let { it > 1 } } == true
        if (selectedConnected != null && usbOutputTargetStore.selectedLabel != selectedConnected.label) {
            usbOutputTargetStore.select(selectedId, selectedConnected.label)
        }
        mutableUsbAudioTargetSelection.value = LazerUsbAudioTargetSnapshot(
            connectedTargets = targets,
            selectedTargetId = selectedId,
            selectedTargetLabel = selectedConnected?.label
                ?: selectedId?.let { targetsByIdentity[it]?.firstOrNull()?.label }
                ?: usbOutputTargetStore.selectedLabel,
            selectedTargetAmbiguous = selectedAmbiguous,
        )
    }

    fun close() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        if (usbUacReceiverRegistered) {
            runCatching { activity.unregisterReceiver(usbUacReceiver) }
            usbUacReceiverRegistered = false
        }
        usbUacGeneration += 1
        pendingUsbUacRequest = null
    }

    private fun registerUsbUacReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_UAC_PERMISSION)
        }
        runCatching {
            ContextCompat.registerReceiver(
                activity,
                usbUacReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            usbUacReceiverRegistered = true
        }
    }

    private fun requestUsbUacOperation(increase: Boolean?) {
        val state = mutableUsbUacVolume.value
        if (state.status == LazerUsbUacVolumeStatus.AwaitingPermission ||
            state.status == LazerUsbUacVolumeStatus.Reading
        ) return
        val id = state.selectedDeviceId ?: return
        val option = state.devices.singleOrNull { it.id == id }
        val device = usbManager.deviceList[id]
        if (option == null || device == null ||
            device.vendorId != option.vendorId || device.productId != option.productId
        ) {
            refreshUsbUacDevices()
            publishUsbUacVolume(mutableUsbUacVolume.value.copy(status = LazerUsbUacVolumeStatus.Failed,
                detail = "settings.hifi.usb_uac.device_removed"))
            return
        }

        usbUacGeneration += 1
        val request = PendingUsbUacRequest(id, device.vendorId, device.productId, increase, usbUacGeneration)
        if (usbManager.hasPermission(device)) {
            performUsbUacOperation(request, device)
            return
        }
        pendingUsbUacRequest = request
        publishUsbUacVolume(state.copy(
            status = LazerUsbUacVolumeStatus.AwaitingPermission,
            currentDb256 = null,
            muted = false,
            ranges = emptyList(),
            canDecrease = false,
            canIncrease = false,
            detail = "settings.hifi.usb_uac.permission_prompt",
        ))
        runCatching {
            val intent = Intent(ACTION_USB_UAC_PERMISSION)
                .setPackage(activity.packageName)
                .putExtra(EXTRA_USB_UAC_GENERATION, request.generation)
            val permissionIntent = PendingIntent.getBroadcast(
                activity,
                request.generation.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            usbManager.requestPermission(device, permissionIntent)
        }.onFailure {
            pendingUsbUacRequest = null
            publishUsbUacVolume(mutableUsbUacVolume.value.copy(
                status = LazerUsbUacVolumeStatus.Failed,
                detail = "settings.hifi.usb_uac.permission_failed",
            ))
        }
    }

    private fun onUsbUacPermissionResult(intent: Intent) {
        val request = pendingUsbUacRequest ?: return
        val grantedDevice = intent.usbDeviceExtra() ?: return
        val callbackGeneration = if (intent.hasExtra(EXTRA_USB_UAC_GENERATION)) {
            intent.getLongExtra(EXTRA_USB_UAC_GENERATION, Long.MIN_VALUE)
        } else null
        if (!androidUacPermissionCallbackMatches(
                requestDeviceId = request.deviceId,
                requestVendorId = request.vendorId,
                requestProductId = request.productId,
                requestGeneration = request.generation,
                currentGeneration = usbUacGeneration,
                selectedDeviceId = mutableUsbUacVolume.value.selectedDeviceId,
                callbackDeviceId = grantedDevice.deviceName,
                callbackVendorId = grantedDevice.vendorId,
                callbackProductId = grantedDevice.productId,
                callbackGeneration = callbackGeneration,
            )
        ) return
        pendingUsbUacRequest = null
        val device = usbManager.deviceList[request.deviceId]
        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false).not()) {
            publishUsbUacVolume(mutableUsbUacVolume.value.copy(
                status = LazerUsbUacVolumeStatus.PermissionDenied,
                detail = "settings.hifi.usb_uac.permission_denied",
            ))
            return
        }
        if (device == null || device.vendorId != request.vendorId || device.productId != request.productId) {
            refreshUsbUacDevices()
            return
        }
        performUsbUacOperation(request, device)
    }

    private fun performUsbUacOperation(request: PendingUsbUacRequest, device: UsbDevice) {
        publishUsbUacVolume(mutableUsbUacVolume.value.copy(
            status = LazerUsbUacVolumeStatus.Reading,
            detail = null,
            canDecrease = false,
            canIncrease = false,
        ))
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = usbManager.openDevice(device)
                        ?: error("USB device could not be opened")
                    try {
                        val control = findAndroidUacPlaybackVolumeControl(connection.rawDescriptors)
                            ?: return@runCatching UsbUacOperationResult.Unsupported
                        val volume = AndroidUacHardwareVolume(
                            control,
                            AndroidUsbConnectionControlTransfer(connection),
                        )
                        val ranges = volume.readVolumeRanges()
                        val current = volume.readCurrentVolume()
                        val updated = if (request.increase == null) {
                            current
                        } else {
                            val direction = if (request.increase) AndroidUacVolumeDirection.Up
                            else AndroidUacVolumeDirection.Down
                            val target = nextAndroidUacVolumeValue(current, ranges, direction)
                                ?: return@runCatching UsbUacOperationResult.Ready(current, ranges)
                            volume.setAndReadBackDb256(target, ranges)
                        }
                        UsbUacOperationResult.Ready(updated, ranges)
                    } finally {
                        connection.close()
                    }
                }
            }
            if (request.generation != usbUacGeneration ||
                mutableUsbUacVolume.value.selectedDeviceId != request.deviceId
            ) return@launch
            val attachedDevice = usbManager.deviceList[request.deviceId]
            if (attachedDevice == null || attachedDevice.vendorId != request.vendorId ||
                attachedDevice.productId != request.productId
            ) {
                refreshUsbUacDevices()
                return@launch
            }
            result.onSuccess { operation ->
                when (operation) {
                    UsbUacOperationResult.Unsupported -> publishUsbUacVolume(
                        mutableUsbUacVolume.value.copy(
                            status = LazerUsbUacVolumeStatus.Unsupported,
                            currentDb256 = null,
                            muted = false,
                            ranges = emptyList(),
                            detail = "settings.hifi.usb_uac.unsupported",
                        ),
                    )

                    is UsbUacOperationResult.Ready -> {
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
                        publishUsbUacVolume(mutableUsbUacVolume.value.copy(
                            status = LazerUsbUacVolumeStatus.Ready,
                            currentDb256 = finiteDb256,
                            muted = operation.volume == AndroidUacVolumeValue.Muted,
                            ranges = operation.ranges.map {
                                LazerUsbUacVolumeRange(it.minimumDb256, it.maximumDb256, it.resolutionDb256)
                            },
                            canDecrease = canDecrease,
                            canIncrease = canIncrease,
                            detail = null,
                        ))
                    }
                }
            }.onFailure {
                publishUsbUacVolume(mutableUsbUacVolume.value.copy(
                    status = LazerUsbUacVolumeStatus.Failed,
                    currentDb256 = null,
                    muted = false,
                    ranges = emptyList(),
                    canDecrease = false,
                    canIncrease = false,
                    detail = "settings.hifi.usb_uac.transfer_failed",
                ))
            }
        }
    }

    private fun publishUsbUacVolume(snapshot: LazerUsbUacVolumeSnapshot) {
        mutableUsbUacVolume.value = snapshot
        LazerUsbUacVolumeStateStore.publish(snapshot)
    }

    private fun hasUsbAudioControlInterface(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any { index ->
            val usbInterface = device.getInterface(index)
            usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                usbInterface.interfaceSubclass == USB_SUBCLASS_AUDIO_CONTROL
        }

    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private data class PendingUsbUacRequest(
        val deviceId: String,
        val vendorId: Int,
        val productId: Int,
        val increase: Boolean?,
        val generation: Long,
    )

    private sealed interface UsbUacOperationResult {
        data object Unsupported : UsbUacOperationResult
        data class Ready(
            val volume: AndroidUacVolumeValue,
            val ranges: List<AndroidUacVolumeRange>,
        ) : UsbUacOperationResult
    }

    override val microphoneGranted: Boolean
        get() = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @Composable
    override fun BackGesture(
        enabled: Boolean,
        onProgress: (progress: Float, edge: LazerSwipeEdge) -> Unit,
        onConfirmed: () -> Unit,
    ) {
        PredictiveBackHandler(enabled = enabled) { events ->
            try {
                events.collect { event ->
                    onProgress(
                        event.progress,
                        if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) {
                            LazerSwipeEdge.Right
                        } else {
                            LazerSwipeEdge.Left
                        },
                    )
                }
                onConfirmed()
            } finally {
                onProgress(0f, LazerSwipeEdge.Left)
            }
        }
    }

    override fun shareText(text: String, title: String) {
        activity.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                title,
            ),
        )
    }

    override fun requestMicrophonePermission(onResult: (granted: Boolean) -> Unit) {
        pendingMicrophone = onResult
        microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun pickBackgroundImage(onPicked: (source: String?) -> Unit) {
        pendingImage = onPicked
        imageLauncher.launch("image/*")
    }

    override fun pickLocalAudioFiles(onPicked: (LazerLocalAudioPickerResult) -> Unit) {
        pendingLocalAudio = onPicked
        localAudioLauncher.launch(arrayOf("audio/*"))
    }

    override fun pickExportDestination(suggestedName: String, onPicked: (target: String?) -> Unit) {
        pendingTarget = onPicked
        exportLauncher.launch(suggestedName)
    }

    override fun scanCode(theme: LazerScanTheme, onResult: (text: String?) -> Unit) {
        pendingScan = onResult
        scanLauncher.launch(
            ScanOptions().apply {
                setBeepEnabled(false)
                setCaptureActivity(LazerScanActivity::class.java)
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setOrientationLocked(false)
                setPrompt(theme.prompt)
                addExtra(LazerScanActivity.EXTRA_TITLE, theme.title)
                addExtra(LazerScanActivity.EXTRA_DESCRIPTION, theme.description)
                addExtra(LazerScanActivity.EXTRA_PROMPT, theme.prompt)
                addExtra(LazerScanActivity.EXTRA_BACK_DESCRIPTION, theme.backLabel)
                addExtra(LazerScanActivity.EXTRA_DARK_THEME, theme.isDark)
                addExtra(LazerScanActivity.EXTRA_BACKGROUND_COLOR, theme.background.toArgb())
                addExtra(LazerScanActivity.EXTRA_SURFACE_COLOR, theme.surface.toArgb())
                addExtra(LazerScanActivity.EXTRA_PRIMARY_COLOR, theme.primary.toArgb())
                addExtra(LazerScanActivity.EXTRA_PRIMARY_CONTAINER_COLOR, theme.primaryContainer.toArgb())
                addExtra(LazerScanActivity.EXTRA_ON_BACKGROUND_COLOR, theme.onBackground.toArgb())
                addExtra(LazerScanActivity.EXTRA_ON_SURFACE_VARIANT_COLOR, theme.onSurfaceVariant.toArgb())
                addExtra(LazerScanActivity.EXTRA_ON_PRIMARY_CONTAINER_COLOR, theme.onPrimaryContainer.toArgb())
            },
        )
    }

    override fun setStatusBarAppearance(isDark: Boolean) {
        val view = activity.window.decorView
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    override fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

    private companion object {
        const val ACTION_USB_UAC_PERMISSION = "dev.naominet.lazer.USB_UAC_PERMISSION"
        const val EXTRA_USB_UAC_GENERATION = "dev.naominet.lazer.USB_UAC_GENERATION"
        const val USB_SUBCLASS_AUDIO_CONTROL = 1

        const val KEY_BACKGROUND = "lazer.background"
        const val KEY_LOCAL_AUDIO = "lazer.local_audio"
        const val KEY_EXPORT = "lazer.export"
        const val KEY_SCAN = "lazer.scan"
        const val KEY_MICROPHONE = "lazer.microphone"
    }
}

/** The sign-in browser: a WebView pinned to the login hosts, with the session carried into it. */
@Composable
fun AndroidNeteaseAuthWebView(
    url: String,
    sessionCookie: String,
    modifier: Modifier,
) {
    val context = LocalView.current.context
    androidx.compose.ui.viewinterop.AndroidView(
        factory = {
            WebView(context).apply {
                val authorizationWebView = this
                setBackgroundColor(android.graphics.Color.WHITE)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(authorizationWebView, false)
                    installNeteaseSessionCookies(sessionCookie)
                    flush()
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = !isAllowedNeteaseWebHost(request.url.host)
                }
                loadUrl(url)
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.destroy()
        },
        modifier = modifier,
    )
}

private fun CookieManager.installNeteaseSessionCookies(sessionCookie: String) {
    val allowed = setOf("MUSIC_U", "MUSIC_A", "NMTID", "deviceId", "__csrf")
    sessionCookie.split(';').forEach { field ->
        val name = field.substringBefore('=').trim()
        val value = field.substringAfter('=', "").trim()
        if (name in allowed && value.isNotBlank()) {
            setCookie(
                "https://music.163.com",
                "$name=$value; Domain=.music.163.com; Path=/; Secure; SameSite=Lax",
            )
        }
    }
}

private fun isAllowedNeteaseWebHost(host: String?): Boolean =
    host.equals("music.163.com", ignoreCase = true) ||
        host.equals("st.music.163.com", ignoreCase = true)

