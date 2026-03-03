package com.webhook.domain.service

/**
 * 도메인/비즈니스 규칙 위반을 나타내는 표준 예외.
 *
 * - Repository/Domain 계층에서 "비즈니스 실패"를 표현할 때 사용한다.
 * - UseCase는 이 예외만 catch하여 이벤트를 FAILED로 확정한다.
 * - RuntimeException(인프라 오류 등)은 잡지 않고 상위로 전파한다.
 */
class WebhookBusinessException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)