package dev.naominet.lazer

import java.nio.file.Files
import java.util.Comparator
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopAppResourceLocatorTest {
    @Test
    fun `resource locator finds packaged windows linux and mac layouts`() {
        val root = Files.createTempDirectory("lazer-resource-locator")
        try {
            val layouts = listOf(
                Triple("windows", "image/Lazer.exe", "image/app/resources/native/windows-x64/lazer-audio.dll"),
                Triple("linux", "image/lib/runtime/bin/java", "image/lib/app/resources/native/linux-x64/liblazer-audio.so"),
                Triple("macos", "Lazer.app/Contents/runtime/Contents/Home/bin/java", "Lazer.app/Contents/app/resources/native/macos-arm64/liblazer-audio.dylib"),
            )
            layouts.forEach { (_, executablePath, resourcePath) ->
                val executable = root.resolve(executablePath)
                Files.createDirectories(executable.parent)
                Files.createFile(executable)
                val resource = root.resolve(resourcePath)
                Files.createDirectories(resource.parent)
                Files.createFile(resource)
                assertEquals(
                    resource.toFile().absoluteFile,
                    WindowsMediaControlIcons.appResourceFile(executable.toFile(), resourcePath.substringAfter("resources/")),
                )
            }
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
