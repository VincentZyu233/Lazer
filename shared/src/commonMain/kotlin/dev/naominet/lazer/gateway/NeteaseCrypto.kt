package dev.naominet.lazer.gateway

import kotlin.io.encoding.Base64

/** Protocol encoding; HTTPS remains responsible for transport security. */
internal object NeteaseCrypto {
    fun secureRandom(size: Int): ByteArray {
        require(size >= 0)
        return gatewaySecureRandom(size)
    }

    fun weapi(json: String): Map<String, String> {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val secret = buildString {
            // Rejection sampling avoids bias when mapping random bytes to the alphabet.
            while (length < 16) {
                secureRandom(16).forEach {
                    val value = it.toInt() and 0xff
                    if (value < 248 && length < 16) append(alphabet[value % 62])
                }
            }
        }
        return weapi(json, secret)
    }

    internal fun weapi(json: String, secret: String): Map<String, String> {
        require(secret.length == 16 && secret.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' })
        val iv = "0102030405060708".encodeToByteArray()
        fun encrypt(text: String, key: String) = Base64.encode(
            gatewayAesCbcEncrypt(text.encodeToByteArray(), key.encodeToByteArray(), iv),
        )
        return mapOf(
            "params" to encrypt(encrypt(json, "0CoJUm6Qyw8W8jud"), secret),
            "encSecKey" to rsaEncode(secret.reversed().encodeToByteArray()),
        )
    }

    fun eapi(path: String, json: String): String {
        val digest = md5("nobody${path}use${json}md5forencrypt")
        val payload = "$path-36cd479b6b5-$json-36cd479b6b5-$digest"
        return gatewayAesEncrypt(payload.encodeToByteArray(), "e82ckenh8dichen8".encodeToByteArray()).hex()
    }

    fun md5(value: String): String = gatewayMd5(value.encodeToByteArray()).hex().lowercase()
}

// The protocol uses raw RSA with a public exponent of 65537. Fixed-size unsigned arithmetic
// keeps this public-key operation identical across JVM, Android, and Native.
private fun rsaEncode(input: ByteArray): String {
    val modulusHex = "e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    val modulus = IntArray(129)
    modulusHex.chunked(2).forEachIndexed { index, byte -> modulus[index + 1] = byte.toInt(16) }
    fun addMod(a: IntArray, b: IntArray): IntArray {
        val result = IntArray(129)
        var carry = 0
        for (i in 128 downTo 0) {
            val sum = a[i] + b[i] + carry
            result[i] = sum and 255
            carry = sum ushr 8
        }
        val firstDifference = result.indices.firstOrNull { result[it] != modulus[it] }
        if (firstDifference == null || result[firstDifference] > modulus[firstDifference]) {
            var borrow = 0
            for (i in 128 downTo 0) {
                val difference = result[i] - modulus[i] - borrow
                result[i] = difference and 255
                borrow = if (difference < 0) 1 else 0
            }
        }
        return result
    }
    fun multiply(a: IntArray, b: IntArray): IntArray {
        var result = IntArray(129)
        for (byte in b) for (bit in 7 downTo 0) {
            result = addMod(result, result)
            if (byte and (1 shl bit) != 0) result = addMod(result, a)
        }
        return result
    }
    val base = IntArray(129)
    input.forEachIndexed { index, byte -> base[129 - input.size + index] = byte.toInt() and 255 }
    var result = base
    repeat(16) { result = multiply(result, result) }
    result = multiply(result, base)
    return result.drop(1).joinToString("") { it.toString(16).padStart(2, '0') }
}

internal fun ByteArray.hex(): String = joinToString("") {
    (it.toInt() and 0xff).toString(16).padStart(2, '0')
}.uppercase()

internal expect fun gatewayAesEncrypt(data: ByteArray, key: ByteArray): ByteArray
internal expect fun gatewayAesCbcEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray
internal expect fun gatewayMd5(data: ByteArray): ByteArray
internal expect fun gatewaySecureRandom(size: Int): ByteArray
