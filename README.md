# Webhook Aladin

알라딘 계정 변경 이벤트를 수신·처리하는 Kotlin/Ktor 기반 Webhook 서버입니다.

---

## 기술 스택

| 항목 | 내용 |
|------|------|
| 언어 | Kotlin 2.1.0 (JVM 21) |
| 서버 | Ktor 2.3.12 (Netty) |
| DB | SQLite (sqlite-jdbc 3.47.2.0) |
| 테스트 | JUnit 5, MockK 1.13.12 |

---

## 실행 방법

### 1. 사전 요구사항

- JDK 21 이상
- Gradle (Wrapper 포함, `./gradlew` 사용 가능)

### 2. 환경 변수

| 변수명 | 필수 | 기본값 | 설명 |
|--------|------|--------|------|
| `WEBHOOK_SECRET` | **필수** | - | HMAC-SHA256 서명 검증에 사용되는 시크릿 키 |
| `WEBHOOK_DB_PATH` | 선택 | `webhook.db` | SQLite DB 파일 경로 |
| `PORT` | 선택 | `8080` | 서버 포트 |

### 3. 빌드 및 실행

```bash
# 환경 변수 설정
export WEBHOOK_SECRET=my-secret-key

# 의존성 다운로드 및 빌드
./gradlew build

# 서버 실행
./gradlew run
```

### 4. 테스트 실행

```bash
# 기본 실행
WEBHOOK_SECRET=test-secret ./gradlew test

# E2E 통합 테스트용 별도 DB 지정 권장
WEBHOOK_SECRET=test-secret WEBHOOK_DB_PATH=build/test-webhook-e2e.db ./gradlew test

# 특정 테스트 클래스만 실행
WEBHOOK_SECRET=test-secret ./gradlew test --tests "com.webhook.HmacSignatureVerifierTest"
```

---

## API 명세 요약

### POST `/webhooks/account-changes`

웹훅 이벤트를 수신합니다.

**Request Headers**

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-Event-Id` | 필수 | 이벤트 고유 식별자 |
| `X-Signature` | 필수 | HMAC-SHA256 서명 (`sha256=<hex>` 형식) |
| `Content-Type` | 권장 | `application/json` |

**Request Body**

```json
{
  "eventType": "EMAIL_FORWARDING_CHANGED",
  "accountId": "acc-001",
  "occurredAt": "2026-03-01T00:00:00Z",
  "payload": { "email": "new@example.com" }
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `eventType` | String | 이벤트 종류 (아래 EventType 참고) |
| `accountId` | String | 대상 계정 ID |
| `occurredAt` | String (ISO-8601) | 이벤트 발생 시각 |
| `payload` | JSON | 이벤트별 추가 데이터 |

**지원 EventType**

| 값 | 처리 내용 |
|----|-----------|
| `ACCOUNT_CREATED` | 계정 생성 (upsert) |
| `ACCOUNT_UPDATED` | 계정 정보 갱신 (upsert) |
| `ACCOUNT_DELETED` | 계정 삭제 → AccountStatus `DELETED` |
| `EMAIL_FORWARDING_CHANGED` | 이메일 전달 주소 변경 |
| `APPLE_ACCOUNT_DELETED` | Apple 계정 삭제 → AccountStatus `APPLE_DELETED` |

**Response (200 OK)**

```json
{
  "eventId": "evt-001",
  "status": "DONE",
  "isDuplicate": false,
  "error": null
}
```

**오류 응답**

| 상태 코드 | 사유 |
|-----------|------|
| `400 Bad Request` | X-Event-Id 누락, 잘못된 JSON, accountId 공백, 잘못된 occurredAt/eventType |
| `401 Unauthorized` | X-Signature 누락 또는 서명 불일치 |
| `500 Internal Server Error` | 서버 내부 오류 |

> 동일 `X-Event-Id`로 재전송된 경우 `isDuplicate: true`로 응답하며 비즈니스 로직은 재실행되지 않습니다. HTTP 상태는 200입니다.

---

### GET `/accounts/{accountId}`

계정 정보를 조회합니다.

**Response (200 OK)**

```json
{
  "accountId": "acc-001",
  "name": "홍길동",
  "email": "user@example.com",
  "status": "ACTIVE",
  "createdAt": "2026-03-01T00:00:00Z",
  "updatedAt": "2026-03-01T00:00:00Z"
}
```

**AccountStatus 값**: `ACTIVE` | `SUSPENDED` | `DELETED` | `APPLE_DELETED`

| 상태 코드 | 사유 |
|-----------|------|
| `404 Not Found` | 계정 없음 |

---

### GET `/events/{eventId}`

이벤트 처리 이력을 조회합니다.

**Response (200 OK)**

```json
{
  "eventId": "evt-001",
  "eventType": "EMAIL_FORWARDING_CHANGED",
  "accountId": "acc-001",
  "status": "DONE",
  "createdAt": "2026-03-01T00:00:00Z",
  "processedAt": "2026-03-01T00:00:01Z",
  "errorMessage": null
}
```

**EventStatus 전이**: `RECEIVED` → `PROCESSING` → `DONE` 또는 `FAILED`

| 상태 코드 | 사유 |
|-----------|------|
| `404 Not Found` | 이벤트 없음 |

---

## 테스트 시나리오 커버리지

### 필수 시나리오 (전체 구현)

| # | 시나리오 | 커버 테스트명 | 테스트 파일 |
|---|----------|---------------|-------------|
| 1 | **서명 검증 성공 케이스** | `정상 서명은 true를 반환한다` | `HmacSignatureVerifierTest` |
| 1 | **서명 검증 실패 케이스** | `서명이 불일치하면 false를 반환한다`<br>`HMAC 불일치 - verifier false이면 401 반환`<br>`서명 실패 - 401 반환되고 이벤트는 저장되지 않아 GET inbox events가 404다` | `HmacSignatureVerifierTest`<br>`WebhookControllerTest`<br>`IntegrationE2EWebhookSpecTest` |
| 2 | **동일 eventId 재전송 시 중복 처리 방지** (DB 유니크/로직) | `insertIfAbsent - 중복 이벤트는 false를 반환하고 기존 데이터를 덮어쓰지 않는다`<br>`중복 이벤트는 isDuplicate true를 반환하고 비즈니스 로직이 실행되지 않는다` | `SqliteEventRepositoryTest`<br>`WebhookProcessingUseCaseTest` |
| 3 | **EMAIL_FORWARDING_CHANGED 처리 후 계정 조회 값이 갱신됨** | `updateEmailForwarding는 email을 갱신한다`<br>`EMAIL_FORWARDING_CHANGED는 updateEmailForwarding을 호출하고 이벤트는 DONE으로 확정된다`<br>`서명 성공 - EMAIL_FORWARDING_CHANGED 처리 후 GET accounts에 갱신된 이메일이 반영됨` | `SqliteAccountRepositoryTest`<br>`WebhookProcessingUseCaseTest`<br>`IntegrationE2EWebhookSpecTest` |
| 4 | **ACCOUNT_DELETED 처리 후 상태가 DELETED로 변경됨** | `deleteOrClose는 status를 DELETED로 변경한다`<br>`ACCOUNT_DELETED는 deleteOrClose를 호출하고 이벤트는 DONE으로 확정된다`<br>`ACCOUNT_DELETED 처리 후 GET accounts에 status=DELETED가 반영됨` | `SqliteAccountRepositoryTest`<br>`WebhookProcessingUseCaseTest`<br>`IntegrationE2EWebhookSpecTest` |
| 5 | **실패 케이스에서 FAILED 기록 및 error_message 저장됨** | `updateStatus - PROCESSING 이벤트를 FAILED로 전이시키고 errorMessage를 저장한다`<br>`도메인 처리 중 예외가 발생하면 이벤트는 FAILED로 기록되고 error_message가 저장된다` | `SqliteEventRepositoryTest`<br>`WebhookProcessingUseCaseTest` |

---

### 추가 테스트 (옵션 - 추가 점수)

#### HmacSignatureVerifierTest (4건 추가)

| 테스트명 | 설명 |
|----------|------|
| `sha256= prefix 없는 서명은 false를 반환한다` | prefix 누락 시 거부 |
| `blank 서명은 false를 반환한다` | 빈 문자열 서명 거부 |
| `hex가 아닌 문자열 서명은 false를 반환한다` | 잘못된 hex 형식 거부 |
| `홀수 길이 hex 서명은 false를 반환한다` | 홀수 길이 hex 거부 |

#### WebhookProcessingUseCaseTest (4건 추가)

| 테스트명 | 설명 |
|----------|------|
| `claim 실패 시 이미 처리 중인 이벤트는 재처리되지 않는다` | PROCESSING 상태 중복 처리 방지 |
| `APPLE_ACCOUNT_DELETED는 markAppleDeleted를 호출하고 이벤트는 DONE으로 확정된다` | Apple 계정 삭제 처리 |
| `지원하지 않는 이벤트 타입은 FAILED로 기록되고 error_message가 저장된다` | 미지원 EventType FAILED 처리 |
| `EventRepository 예외는 설계된 실패 정책에 따라 상위로 전파된다` | DB 예외 전파 정책 |

#### SqliteEventRepositoryTest (6건 추가)

| 테스트명 | 설명 |
|----------|------|
| `claimForProcessing - RECEIVED 이벤트는 true를 반환하고 PROCESSING으로 전이된다` | claim 성공 전이 |
| `claimForProcessing - 이미 PROCESSING 이벤트는 false를 반환하고 상태가 유지된다` | 이중 claim 방지 |
| `claimForProcessing - DONE 이벤트는 false를 반환하고 DONE 상태가 유지된다` | 완료 이벤트 재처리 방지 |
| `claimForProcessing - FAILED 이벤트는 false를 반환하고 FAILED 상태가 유지된다` | 실패 이벤트 재처리 방지 |
| `updateStatus - PROCESSING 이벤트를 DONE으로 전이시킨다` | 정상 완료 상태 전이 |
| `claimForProcessing - 동일 이벤트에 연속 두 번 호출 시 첫 번째만 true를 반환한다` | 동시성/경합 최소 검증 |

#### SqliteAccountRepositoryTest (4건 추가)

| 테스트명 | 설명 |
|----------|------|
| `updateEmailForwarding는 계정이 없으면 WebhookBusinessException을 던진다` | 존재하지 않는 계정 예외 |
| `markAppleDeleted는 status를 APPLE_DELETED로 변경한다` | Apple 삭제 상태 변경 |
| `markAppleDeleted는 계정이 없으면 WebhookBusinessException을 던진다` | 존재하지 않는 계정 예외 |
| `deleteOrClose는 계정이 없으면 WebhookBusinessException을 던진다` | 존재하지 않는 계정 예외 |

#### WebhookControllerTest (6건 추가)

| 테스트명 | 설명 |
|----------|------|
| `X-Signature 헤더 없으면 401 반환하고 useCase 미호출` | 서명 헤더 누락 거부 |
| `잘못된 JSON body이면 400 반환하고 useCase 미호출` | 잘못된 JSON 거부 |
| `accountId 공백이면 400 반환하고 useCase 미호출` | 빈 accountId 거부 |
| `정상 처리 DONE이면 200 반환하고 useCase 1회 호출` | 정상 처리 흐름 |
| `처리 결과 FAILED여도 200 반환` | FAILED 결과도 HTTP 200 |
| `useCase RuntimeException이면 500 반환` | 내부 오류 500 처리 |

#### EventStateMachineTest (8건 추가)

| 테스트명 | 설명 |
|----------|------|
| `RECEIVED to PROCESSING is allowed` | 허용 전이 |
| `PROCESSING to DONE is allowed` | 허용 전이 |
| `PROCESSING to FAILED is allowed` | 허용 전이 |
| `DONE to PROCESSING is not allowed` | 금지 전이 |
| `FAILED to PROCESSING is not allowed` | 금지 전이 |
| `requireTransition does not throw for allowed transitions` | 정상 전이 예외 없음 |
| `requireTransition throws for DONE to PROCESSING` | 불법 전이 예외 발생 |
| `requireTransition throws for FAILED to PROCESSING` | 불법 전이 예외 발생 |
