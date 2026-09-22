package dev.naominet.lazer

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.COM.COMInvoker
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/** Windows taskbar right-click media tasks backed by ICustomDestinationList. */
internal object WindowsJumpList {
    private const val VT_RELEASE = 2
    private const val VT_DESTINATION_SET_APP_ID = 3
    private const val VT_DESTINATION_BEGIN_LIST = 4
    private const val VT_DESTINATION_ADD_USER_TASKS = 7
    private const val VT_DESTINATION_COMMIT_LIST = 8
    private const val VT_DESTINATION_ABORT_LIST = 11
    private const val VT_OBJECT_COLLECTION_ADD_OBJECT = 5
    private const val VT_SHELL_LINK_SET_DESCRIPTION = 7
    // IShellLinkW inherits IUnknown. GetArguments/GetIconLocation immediately precede
    // their setters, so the setters are at 11 and 17 respectively.
    private const val VT_SHELL_LINK_SET_ARGUMENTS = 11
    private const val VT_SHELL_LINK_SET_ICON_LOCATION = 17
    private const val VT_SHELL_LINK_SET_PATH = 20
    private const val VT_PROPERTY_STORE_SET_VALUE = 6
    private const val VT_PROPERTY_STORE_COMMIT = 7

    private val clsidDestinationList = Guid.GUID.fromString("{77F10CF0-3DB5-4966-B520-B7C54FD35ED6}")
    private val iidDestinationList = Guid.GUID.fromString("{6332DEBF-87B5-4670-90C0-5E57B408A49E}")
    private val clsidObjectCollection = Guid.GUID.fromString("{2D3468C1-36A7-43B6-AC24-D3F02FD9607A}")
    private val iidObjectCollection = Guid.GUID.fromString("{5632B1A4-E38A-400A-928A-D4CD63230295}")
    private val iidObjectArray = Guid.GUID.fromString("{92CA9DCD-5622-4BBA-A805-5E9F541BD8C9}")
    private val clsidShellLink = Guid.GUID.fromString("{00021401-0000-0000-C000-000000000046}")
    private val iidShellLinkW = Guid.GUID.fromString("{000214F9-0000-0000-C000-000000000046}")
    private val iidPropertyStore = Guid.GUID.fromString("{886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99}")
    private val appUserModelIdKey = PROPERTYKEY(
        Guid.GUID.fromString("{9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3}"),
        5,
    ).apply { write() }
    private val titleKey = PROPERTYKEY(
        Guid.GUID.fromString("{F29F85E0-4FF9-1068-AB91-08002B27B3D9}"),
        2,
    ).apply { write() }

    private const val CLSCTX_INPROC_SERVER = 0x1
    private const val APP_ID = "dev.naominet.lazer"

    fun prepareAppUserModelId() {
        if (!isWindowsDesktop()) return
        runCatching {
            checkHr(
                "SetCurrentProcessExplicitAppUserModelID",
                Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(WString(APP_ID)).toInt(),
            )
        }.onFailure { error ->
            PlaybackDebugLog.event("jump-list-app-id-error", error.playbackDebugSummary())
        }
    }

    fun install(isPlaying: Boolean) {
        if (!isWindowsDesktop()) return
        runCatching {
            val executable = ProcessHandle.current().info().command().orElse(null)
                ?.takeIf { it.endsWith(".exe", ignoreCase = true) }
                ?: return
            Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, 0)
            val destinationList = create(clsidDestinationList, iidDestinationList) ?: return
            try {
                checkHr(invoke(destinationList, VT_DESTINATION_SET_APP_ID, arrayOf(destinationList, WString(APP_ID))))
                val removed = PointerByReference()
                checkHr(
                    invoke(
                        destinationList,
                        VT_DESTINATION_BEGIN_LIST,
                        arrayOf(destinationList, IntByReference(), iidObjectArray, removed),
                    ),
                )
                val tasks = create(clsidObjectCollection, iidObjectCollection) ?: return
                try {
                    addTask(tasks, executable, WindowsMediaCommand.Previous, tr("media_control.previous"))
                    addTask(
                        tasks,
                        executable,
                        WindowsMediaCommand.PlayPause,
                        if (isPlaying) tr("media_control.pause") else tr("media_control.play"),
                    )
                    addTask(tasks, executable, WindowsMediaCommand.Next, tr("media_control.next"))
                    val taskArray = queryInterface(tasks, iidObjectArray) ?: error("Unable to query IObjectArray")
                    try {
                        checkHr(
                            "ICustomDestinationList.AddUserTasks",
                            invoke(destinationList, VT_DESTINATION_ADD_USER_TASKS, arrayOf(destinationList, taskArray)),
                        )
                    } finally {
                        release(taskArray)
                    }
                    checkHr(invoke(destinationList, VT_DESTINATION_COMMIT_LIST, arrayOf(destinationList)))
                    PlaybackDebugLog.event("jump-list-installed")
                } catch (error: Throwable) {
                    invoke(destinationList, VT_DESTINATION_ABORT_LIST, arrayOf(destinationList))
                    throw error
                } finally {
                    release(tasks)
                    removed.value?.let(::release)
                }
            } finally {
                release(destinationList)
            }
        }.onFailure { error ->
            PlaybackDebugLog.event("jump-list-install-error", error.playbackDebugSummary())
        }
    }

    private fun addTask(tasks: Pointer, executable: String, command: WindowsMediaCommand, label: String) {
        val link = create(clsidShellLink, iidShellLinkW) ?: error("Unable to create IShellLinkW")
        try {
            checkHr("IShellLinkW.SetPath", invoke(link, VT_SHELL_LINK_SET_PATH, arrayOf(link, WString(executable))))
            checkHr(
                "IShellLinkW.SetArguments",
                invoke(link, VT_SHELL_LINK_SET_ARGUMENTS, arrayOf(link, WString(MEDIA_COMMAND_ARGUMENT + command.argument))),
            )
            checkHr("IShellLinkW.SetDescription", invoke(link, VT_SHELL_LINK_SET_DESCRIPTION, arrayOf(link, WString(label))))
            checkHr(
                "IShellLinkW.SetIconLocation",
                invoke(link, VT_SHELL_LINK_SET_ICON_LOCATION, arrayOf(link, WString(executable), 0)),
            )
            setShellLinkProperties(link, label)
            checkHr("IObjectCollection.AddObject", invoke(tasks, VT_OBJECT_COLLECTION_ADD_OBJECT, arrayOf(tasks, link)))
        } finally {
            release(link)
        }
    }

    private fun setShellLinkProperties(link: Pointer, label: String) {
        val propertyStore = queryInterface(link, iidPropertyStore) ?: error("Unable to query IPropertyStore")
        try {
            val appId = wideStringMemory(APP_ID)
            val title = wideStringMemory(label)
            val appIdValue = PROPVARIANT_LPWSTR(appId).apply { write() }
            val titleValue = PROPVARIANT_LPWSTR(title).apply { write() }
            checkHr(
                "IPropertyStore.SetValue(PKEY_AppUserModel_ID)",
                invoke(
                    propertyStore,
                    VT_PROPERTY_STORE_SET_VALUE,
                    arrayOf(propertyStore, appUserModelIdKey.pointer, appIdValue.pointer),
                ),
            )
            checkHr(
                "IPropertyStore.SetValue(PKEY_Title)",
                invoke(
                    propertyStore,
                    VT_PROPERTY_STORE_SET_VALUE,
                    arrayOf(propertyStore, titleKey.pointer, titleValue.pointer),
                ),
            )
            checkHr("IPropertyStore.Commit", invoke(propertyStore, VT_PROPERTY_STORE_COMMIT, arrayOf(propertyStore)))
        } finally {
            release(propertyStore)
        }
    }

    private fun wideStringMemory(value: String): Memory =
        Memory((value.length + 1L) * 2).apply { setWideString(0, value) }

    private fun create(clsid: Guid.GUID, iid: Guid.GUID): Pointer? {
        val reference = PointerByReference()
        val hr = Ole32.INSTANCE.CoCreateInstance(clsid, Pointer.NULL, CLSCTX_INPROC_SERVER, iid, reference)
        return reference.value?.takeIf { COMUtils.SUCCEEDED(hr) }
    }

    private fun release(pointer: Pointer) {
        invoke(pointer, VT_RELEASE, arrayOf(pointer))
    }

    private fun queryInterface(pointer: Pointer, iid: Guid.GUID): Pointer? {
        val reference = PointerByReference()
        val hr = invoke(pointer, 0, arrayOf(pointer, iid, reference))
        return reference.value?.takeIf { COMUtils.SUCCEEDED(HRESULT(hr)) }
    }

    private fun checkHr(value: Int) = checkHr("COM call", value)

    private fun checkHr(operation: String, value: Int) {
        check(COMUtils.SUCCEEDED(HRESULT(value))) { "$operation HRESULT=0x${value.toUInt().toString(16)}" }
    }

    private fun invoke(pointer: Pointer, index: Int, args: Array<Any>): Int =
        Invoker(pointer).callInt(index, args)

    private class Invoker(pointer: Pointer) : COMInvoker() {
        init { this.pointer = pointer }
        fun callInt(index: Int, args: Array<Any>): Int = _invokeNativeInt(index, args)
    }
}

@Structure.FieldOrder("fmtid", "pid")
internal class PROPERTYKEY(
    @JvmField var fmtid: Guid.GUID,
    @JvmField var pid: Int,
) : Structure()

/** Minimal PROPVARIANT for VT_LPWSTR; its string is borrowed for the immediate COM call. */
@Structure.FieldOrder("vt", "reserved1", "reserved2", "reserved3", "pwszVal")
internal class PROPVARIANT_LPWSTR(
    @JvmField var pwszVal: Pointer,
) : Structure() {
    @JvmField var vt: Short = 31 // VT_LPWSTR
    @JvmField var reserved1: Short = 0
    @JvmField var reserved2: Short = 0
    @JvmField var reserved3: Short = 0
}
