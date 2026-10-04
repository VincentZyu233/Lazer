package dev.naominet.lazer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LazerAudioNativePlatformTest {
    @Test
    fun `native bridge artifact follows host operating system and architecture`() {
        assertEquals(
            LazerAudioNativeArtifact("windows-x64", "lazer-audio.dll", "native/windows-x64/lazer-audio.dll"),
            resolveLazerAudioNativeArtifact("Windows 11", "AMD64"),
        )
        assertEquals(
            LazerAudioNativeArtifact("windows-arm64", "lazer-audio.dll", "native/windows-arm64/lazer-audio.dll"),
            resolveLazerAudioNativeArtifact("Windows 11", "aarch64"),
        )
        assertEquals(
            LazerAudioNativeArtifact("linux-x64", "liblazer-audio.so", "native/linux-x64/liblazer-audio.so"),
            resolveLazerAudioNativeArtifact("Linux", "x86_64"),
        )
        assertEquals(
            LazerAudioNativeArtifact("macos-arm64", "liblazer-audio.dylib", "native/macos-arm64/liblazer-audio.dylib"),
            resolveLazerAudioNativeArtifact("Mac OS X", "arm64"),
        )
        assertNull(resolveLazerAudioNativeArtifact("FreeBSD", "x86_64"))
        assertNull(resolveLazerAudioNativeArtifact("Linux", "riscv64"))
    }
}
