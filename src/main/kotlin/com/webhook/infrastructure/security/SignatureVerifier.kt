package com.webhook.infrastructure.security

interface SignatureVerifier {
    /**
     * 요청 원문(raw body)과 서명 헤더 값을 비교해 HMAC 유효성을 검증한다.
     * @param rawBody 서명 계산에 사용된 요청 바디 원본 바이트
     * @param signatureHeader X-Signature 등 헤더에서 추출한 서명 문자열
     * @return true = 유효한 서명, false = 위조/불일치
     */
    fun verify(rawBody: ByteArray, signatureHeader: String): Boolean
}