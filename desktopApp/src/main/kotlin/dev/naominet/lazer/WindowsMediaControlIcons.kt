package dev.naominet.lazer

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

/** Windows application appearance used by taskbar media-control icons. */
internal enum class WindowsAppTheme(val resourceDirectory: String) {
    Light("light"),
    Dark("dark"),
}

internal fun windowsAppTheme(appsUseLightTheme: Int?): WindowsAppTheme =
    if (appsUseLightTheme == 0) WindowsAppTheme.Dark else WindowsAppTheme.Light

/**
 * Resolves media-control ICO files for both Windows taskbar integrations.
 *
 * Packaged applications use jpackage's stable `app/resources` path so Shell links remain valid.
 * Development runs extract the same classpath resource once because LoadImage and IShellLinkW
 * both require a filesystem path.
 */
internal object WindowsMediaControlIcons {
    private const val PERSONALIZE_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
    private const val APPS_USE_LIGHT_THEME = "AppsUseLightTheme"

    private val extractedResources = mutableMapOf<String, File>()

    fun currentTheme(): WindowsAppTheme {
        if (!isWindowsDesktop()) return WindowsAppTheme.Light
        val value = runCatching {
            Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, PERSONALIZE_KEY, APPS_USE_LIGHT_THEME)
        }.getOrNull()
        return windowsAppTheme(value)
    }

    fun iconFile(name: String): File? {
        val resourcePaths = resourcePaths(currentTheme(), name)
        resourcePaths.firstNotNullOfOrNull(::appResourceFile)?.let { return it }
        return resourcePaths.firstNotNullOfOrNull(::classpathResourceFile)
    }

    /** Finds a stable jpackage resource file without falling back to classpath extraction. */
    fun appResourceFile(resourcePath: String): File? {
        val executable = ProcessHandle.current().info().command().orElse(null) ?: return null
        val appRoot = File(executable).parentFile ?: return null
        return listOf(
            File(appRoot, "app/resources/$resourcePath"),
            File(appRoot, "resources/$resourcePath"),
        ).firstOrNull(File::isFile)
    }

    internal fun resourcePath(theme: WindowsAppTheme, name: String): String =
        "media-control/${theme.resourceDirectory}/$name.ico"

    private fun resourcePaths(theme: WindowsAppTheme, name: String): List<String> = buildList {
        add(resourcePath(theme, name))
        if (theme != WindowsAppTheme.Light) add(resourcePath(WindowsAppTheme.Light, name))
        // Keeps existing development installations functional if a resource update is incomplete.
        add("media-control/$name.ico")
    }

    @Synchronized
    internal fun classpathResourceFile(resourcePath: String): File? {
        extractedResources[resourcePath]?.let { return it }
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath) ?: return null
        return runCatching {
            val suffix = "-${File(resourcePath).name}"
            val output = File.createTempFile("lazer-media-control-", suffix).apply { deleteOnExit() }
            stream.use { input -> output.outputStream().use(input::copyTo) }
            output.also { extractedResources[resourcePath] = it }
        }.getOrNull()
    }
}
