@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.naominet.lazer.gateway

import kotlinx.cinterop.*
import platform.CoreCrypto.*
import platform.posix.size_tVar

internal actual fun gatewayAesEncrypt(data: ByteArray, key: ByteArray): ByteArray =
    gatewayAesCbcEncrypt(data, key, null)

internal actual fun gatewayAesCbcEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray?): ByteArray = memScoped {
    val output = ByteArray(data.size + 16)
    val written = alloc<size_tVar>()
    val options = kCCOptionPKCS7Padding or if (iv == null) kCCOptionECBMode else 0
    val ivBytes = iv ?: ByteArray(0)
    val status = data.usePinned { input ->
        key.usePinned { secret ->
            output.usePinned { result ->
                ivBytes.usePinned { parameter ->
                    CCCrypt(
                        kCCEncrypt, kCCAlgorithmAES, options,
                        secret.addressOf(0), key.size.convert(), parameter.addressOf(0),
                        input.addressOf(0), data.size.convert(), result.addressOf(0),
                        output.size.convert(), written.ptr,
                    )
                }
            }
        }
    }
    check(status == kCCSuccess) { "AES encoding failed." }
    output.copyOf(written.value.toInt())
}

internal actual fun gatewayMd5(data: ByteArray): ByteArray {
    val output = ByteArray(16)
    // A nonempty backing array permits pinning even for the empty-string digest.
    val input = if (data.isEmpty()) byteArrayOf(0) else data
    input.usePinned { source ->
        output.usePinned { target ->
            CC_MD5(source.addressOf(0), data.size.convert(), target.addressOf(0).reinterpret())
        }
    }
    return output
}

internal actual fun gatewaySecureRandom(size: Int): ByteArray {
    require(size >= 0)
    val output = ByteArray(size)
    if (output.isNotEmpty()) {
        val status = output.usePinned {
            SecRandomCopyBytes(kSecRandomDefault, output.size.convert(), it.addressOf(0))
        }
        check(status == 0) { "Secure random generation failed." }
    }
    return output
}
