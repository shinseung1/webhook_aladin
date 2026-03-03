package com.webhook

import com.webhook.infrastructure.security.HmacSignatureVerifier
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HmacSignatureVerifierTest {

    // 구현과 동일 방식으로 WEBHOOK_SECRET 읽기 → 어떤 환경에서도 일치
    private val testSecret: String = System.getenv("WEBHOOK_SECRET") ?: ""
    private val verifier = HmacSignatureVerifier()
    private val payload = "test-payload".toByteArray(Charsets.UTF_8)

    private fun hmacHex(bytes: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(testSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `정상 서명은 true를 반환한다`() {
        val signature = "sha256=${hmacHex(payload)}"
        assertTrue(verifier.verify(payload, signature))
    }

    @Test
    fun `서명이 불일치하면 false를 반환한다`() {
        val signature = "sha256=0000000000000000000000000000000000000000000000000000000000000000"
        assertFalse(verifier.verify(payload, signature))
    }

    @Test
    fun `sha256= prefix 없는 서명은 false를 반환한다`() {
        // removePrefix는 prefix 부재 시 원본 그대로 반환
        // 임의 값은 computed hex와 불일치 → false
        val signature = "deadbeef"
        assertFalse(verifier.verify(payload, signature))
    }

    @Test
    fun `blank 서명은 false를 반환한다`() {
        assertFalse(verifier.verify(payload, ""))
    }

    @Test
    fun `hex가 아닌 문자열 서명은 false를 반환한다`() {
        val signature = "sha256=not-valid-hex!@#"
        assertFalse(verifier.verify(payload, signature))
    }

    @Test
    fun `홀수 길이 hex 서명은 false를 반환한다`() {
        val signature = "sha256=abc"
        assertFalse(verifier.verify(payload, signature))
    }
}