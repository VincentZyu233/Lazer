package dev.naominet.lazer.gateway

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal actual fun gatewayAesEncrypt(data: ByteArray, key: ByteArray): ByteArray =
    Cipher.getInstance("AES/ECB/PKCS5Padding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        doFinal(data)
    }

internal actual fun gatewayAesCbcEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
    Cipher.getInstance("AES/CBC/PKCS5Padding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        doFinal(data)
    }

internal actual fun gatewayMd5(data: ByteArray): ByteArray =
    MessageDigest.getInstance("MD5").digest(data)

internal actual fun gatewaySecureRandom(size: Int): ByteArray =
    ByteArray(size).also { SecureRandom().nextBytes(it) }
