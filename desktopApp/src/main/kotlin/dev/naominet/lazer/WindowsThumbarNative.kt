package dev.naominet.lazer

import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.ptr.PointerByReference
import java.io.File

/**
 * ITaskbarList3 的裸 COM 调用封装,负责把缩略图工具栏按钮下发到 Windows 任务栏。
 *
 * JNA 未内置 ITaskbarList3,故手动:CoCreateInstance 创建实例 -> HrInit -> 按 vtable 索引
 * 调 ThumbBarAddButtons / ThumbBarUpdateButtons。vtable 布局(继承自 IUnknown 的 3 个方法在前):
 *   3 HrInit, 4 AddTab, 5 DeleteTab, 6 ActivateTab, 7 SetActiveAlt, 8 MarkFullscreenWindow,
 *   9 SetProgressValue, 10 SetProgressState, 11 RegisterTab, 12 UnregisterTab, 13 SetTabOrder,
 *   14 SetTabActive, 15 ThumbBarAddButtons, 16 ThumbBarUpdateButtons, 17 ThumbBarSetImageList, ...
 */
internal object WindowsThumbarNative {
    private const val VT_HRINIT = 3
    private const val VT_THUMBBAR_ADD = 15
    private const val VT_THUMBBAR_UPDATE = 16

    private val CLSID_TaskbarList = Guid.GUID.fromString("{56FDF344-FD6D-11d0-958A-006097C9A090}")
    private val IID_ITaskbarList3 = Guid.GUID.fromString("{EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF}")

    private const val CLSCTX_INPROC_SERVER = 0x1
    private const val THB_ICON = 0x2
    private const val THB_TOOLTIP = 0x4
    private const val THB_FLAGS = 0x8
    private const val THBF_ENABLED = 0x0

    private const val CMD_PREVIOUS = 0x1001
    private const val CMD_PLAYPAUSE = 0x1002
    private const val CMD_NEXT = 0x1003

    private const val IMAGE_ICON = 1
    private const val LR_LOADFROMFILE = 0x10

    private var taskbar: Pointer? = null
    private var iconsLoaded = false
    private var prevIcon: Pointer? = null
    private var playIcon: Pointer? = null
    private var pauseIcon: Pointer? = null
    private var nextIcon: Pointer? = null

    /** 首次安装按钮(ThumbBarAddButtons)。 */
    fun ensureButtons(hwnd: HWND, isPlaying: Boolean): Boolean {
        val tb = ensureTaskbar() ?: return false
        loadIcons()
        val buttons = buildButtons(isPlaying)
        val hr = invokeThumbBar(tb, VT_THUMBBAR_ADD, hwnd, buttons)
        if (!COMUtils.SUCCEEDED(HRESULT(hr))) {
            PlaybackDebugLog.event("thumbar-add-failed", "hr=0x${hr.toUInt().toString(16)}")
            return false
        }
        return true
    }

    /** 刷新按钮(ThumbBarUpdateButtons),用于播放/暂停图标切换。 */
    fun updateButtons(hwnd: HWND, isPlaying: Boolean) {
        val tb = taskbar ?: return
        val buttons = buildButtons(isPlaying)
        val hr = invokeThumbBar(tb, VT_THUMBBAR_UPDATE, hwnd, buttons)
        if (!COMUtils.SUCCEEDED(HRESULT(hr))) {
            PlaybackDebugLog.event("thumbar-update-failed", "hr=0x${hr.toUInt().toString(16)}")
        }
    }

    private fun ensureTaskbar(): Pointer? {
        taskbar?.let { return it }
        Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, 0)
        val ref = PointerByReference()
        val hr = Ole32.INSTANCE.CoCreateInstance(
            CLSID_TaskbarList, Pointer.NULL, CLSCTX_INPROC_SERVER, IID_ITaskbarList3, ref,
        )
        if (!COMUtils.SUCCEEDED(hr)) {
            PlaybackDebugLog.event("thumbar-cocreate-failed", "hr=0x${hr.toInt().toUInt().toString(16)}")
            return null
        }
        val tb = ref.value
        // HrInit() 必须在使用任何 taskbar 方法前调用
        val initHr = invokeNoArg(tb, VT_HRINIT)
        if (!COMUtils.SUCCEEDED(HRESULT(initHr))) {
            PlaybackDebugLog.event("thumbar-hrinit-failed", "hr=0x${initHr.toUInt().toString(16)}")
            return null
        }
        taskbar = tb
        return tb
    }

    /**
     * 构造 3 个 THUMBBUTTON 的连续内存数组(toArray 保证连续内存)。首元素指针即整个数组基址。
     */
    private fun buildButtons(isPlaying: Boolean): Array<THUMBBUTTON> {
        @Suppress("UNCHECKED_CAST")
        val array = THUMBBUTTON().toArray(3) as Array<THUMBBUTTON>
        fun fill(b: THUMBBUTTON, id: Int, icon: Pointer?, tip: String) {
            b.dwMask = THB_ICON or THB_TOOLTIP or THB_FLAGS
            b.iId = id
            b.hIcon = icon ?: Pointer.NULL
            b.dwFlags = THBF_ENABLED
            val chars = tip.take(259).toCharArray()
            for (i in chars.indices) b.szTip[i] = chars[i]
            b.szTip[chars.size] = ' '
            b.write()
        }
        fill(array[0], CMD_PREVIOUS, prevIcon, tr("media_control.previous"))
        fill(array[1], CMD_PLAYPAUSE, if (isPlaying) pauseIcon else playIcon,
            if (isPlaying) tr("media_control.pause") else tr("media_control.play"))
        fill(array[2], CMD_NEXT, nextIcon, tr("media_control.next"))
        return array
    }

    /** 调 ThumbBarAddButtons/UpdateButtons: (this, cButtons, THUMBBUTTON*)。 */
    private fun invokeThumbBar(tb: Pointer, vtblIndex: Int, hwnd: HWND, buttons: Array<THUMBBUTTON>): Int =
        COMInvokerAccess.invokeInt(
            tb, vtblIndex,
            arrayOf(tb, hwnd, buttons.size, buttons[0].pointer),
        )

    private fun invokeNoArg(tb: Pointer, vtblIndex: Int): Int =
        COMInvokerAccess.invokeInt(tb, vtblIndex, arrayOf(tb))

    private fun loadIcons() {
        if (iconsLoaded) return
        prevIcon = loadIcon("previous")
        playIcon = loadIcon("play")
        pauseIcon = loadIcon("pause")
        nextIcon = loadIcon("next")
        iconsLoaded = true
    }

    /**
     * 从 jar 资源提取 ico 到临时文件后用 LoadImage 载入 HICON(LoadImage 需文件路径,不能读 jar 流)。
     */
    private fun loadIcon(name: String): Pointer? = runCatching {
        val stream = javaClass.classLoader.getResourceAsStream("media-control/$name.ico") ?: return null
        val tmp = File.createTempFile("lazer-thumb-$name", ".ico").apply { deleteOnExit() }
        stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val handle = User32.INSTANCE.LoadImage(null, tmp.absolutePath, IMAGE_ICON, 0, 0, LR_LOADFROMFILE)
        handle?.pointer
    }.getOrNull()
}

/** THUMBBUTTON 结构(见 shobjidl_core.h)。szTip 为 260 个 WCHAR。 */
@Structure.FieldOrder("dwMask", "iId", "iBitmap", "hIcon", "szTip", "dwFlags")
internal open class THUMBBUTTON : Structure() {
    @JvmField var dwMask: Int = 0
    @JvmField var iId: Int = 0
    @JvmField var iBitmap: Int = 0
    @JvmField var hIcon: Pointer = Pointer.NULL
    @JvmField var szTip: CharArray = CharArray(260)
    @JvmField var dwFlags: Int = 0
}

/** 借道 COMInvoker 的受保护方法按 vtable 索引发起 COM 调用。 */
private object COMInvokerAccess {
    fun invokeInt(thisPtr: Pointer, vtblIndex: Int, args: Array<Any>): Int =
        ThumbInvoker(thisPtr).callInt(vtblIndex, args)

    private class ThumbInvoker(pointer: Pointer) : com.sun.jna.platform.win32.COM.COMInvoker() {
        init { this.pointer = pointer }
        fun callInt(index: Int, args: Array<Any>): Int = _invokeNativeInt(index, args)
    }
}
