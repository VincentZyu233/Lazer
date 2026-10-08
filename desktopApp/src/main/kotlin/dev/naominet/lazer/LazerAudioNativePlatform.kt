package dev.naominet.lazer

/** Native library names are kept architecture-specific so a packaged JVM never loads a foreign ABI. */
internal data class LazerAudioNativeArtifact(
    val platformId: String,
    val fileName: String,
    val resourcePath: String,
)

internal fun resolveLazerAudioNativeArtifact(
    osName: String,
    osArch: String,
): LazerAudioNativeArtifact? {
    val arch = when (osArch.lowercase()) {
        "x86_64", "amd64", "x64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> return null
    }
    val platform = when {
        osName.contains("win", ignoreCase = true) -> "windows"
        osName.contains("mac", ignoreCase = true) || osName.contains("darwin", ignoreCase = true) -> "macos"
        osName.contains("linux", ignoreCase = true) -> "linux"
        else -> return null
    }
    val fileName = when (platform) {
        "windows" -> "lazer-audio.dll"
        "macos" -> "liblazer-audio.dylib"
        else -> "liblazer-audio.so"
    }
    val platformId = "$platform-$arch"
    return LazerAudioNativeArtifact(platformId, fileName, "native/$platformId/$fileName")
}
