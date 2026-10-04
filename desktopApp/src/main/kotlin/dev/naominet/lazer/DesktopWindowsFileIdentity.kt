package dev.naominet.lazer

import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinNT
import java.io.IOException
import java.nio.file.Path

/** Reads the Windows volume serial and 128-bit file ID, which the JDK provider may omit. */
internal object DesktopWindowsFileIdentity {
    private const val FILE_ID_INFO_CLASS = 18
    private const val INVALID_HANDLE_VALUE = -1L
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** Returns null off Windows. A Windows query failure is surfaced so callers can fail closed. */
    @Throws(IOException::class)
    fun read(path: Path): String? {
        if (!isWindows) return null

        val kernel32 = Kernel32.INSTANCE
        val handle = kernel32.CreateFile(
            path.toString(),
            WinNT.FILE_READ_ATTRIBUTES,
            WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE or WinNT.FILE_SHARE_DELETE,
            null,
            WinNT.OPEN_EXISTING,
            WinNT.FILE_FLAG_OPEN_REPARSE_POINT,
            null,
        )
        if (handle == null || Pointer.nativeValue(handle.pointer) == INVALID_HANDLE_VALUE) {
            throw IOException("Unable to open the local audio file to read its Windows identity (error ${kernel32.GetLastError()}).")
        }

        try {
            val info = FileIdInfo()
            if (!kernel32.GetFileInformationByHandleEx(handle, FILE_ID_INFO_CLASS, info.pointer, DWORD(info.size().toLong()))) {
                throw IOException("Unable to read the local audio file's Windows identity (error ${kernel32.GetLastError()}).")
            }
            info.read()
            return buildString(48) {
                append(java.lang.Long.toUnsignedString(info.volumeSerialNumber, 16))
                append(':')
                info.fileId.forEach { byte -> append((byte.toInt() and 0xff).toString(16).padStart(2, '0')) }
            }
        } finally {
            kernel32.CloseHandle(handle)
        }
    }

    @Structure.FieldOrder("volumeSerialNumber", "fileId")
    class FileIdInfo : Structure() {
        @JvmField
        var volumeSerialNumber: Long = 0

        @JvmField
        var fileId: ByteArray = ByteArray(16)
    }
}
