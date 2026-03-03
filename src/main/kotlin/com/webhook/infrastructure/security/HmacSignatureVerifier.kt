package com.webhook.infrastructure.security

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HmacSignatureVerifier : SignatureVerifier {

    private val secret: ByteArray = run {
        val env = System.getenv("WEBHOOK_SECRET")
            ?: throw IllegalStateException("WEBHOOK_SECRET environment variable is not set")
        env.toByteArray(Charsets.UTF_8)
    }

    override fun verify(rawBody: ByteArray, signatureHeader: String): Boolean {
        if (signatureHeader.isBlank()) return false
        if (!signatureHeader.startsWith("sha256=")) return false

        val hexPart = signatureHeader.removePrefix("sha256=")

        val expectedBytes = try {
            hexPart.decodeHex()
        } catch (_: Exception) {
            return false
        }

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val computed = mac.doFinal(rawBody)


        val computedHex = computed.joinToString("") { "%02x".format(it) }

        return MessageDigest.isEqual(computed, expectedBytes)
    }

    private fun String.decodeHex(): ByteArray {
        if (length % 2 != 0) throw IllegalArgumentException("Odd-length hex string")
        return ByteArray(length / 2) { i ->
            val hi = Character.digit(this[i * 2], 16)
            val lo = Character.digit(this[i * 2 + 1], 16)
            if (hi == -1 || lo == -1) throw IllegalArgumentException("Invalid hex character at index $i")
            ((hi shl 4) or lo).toByte()
        }
    }
}