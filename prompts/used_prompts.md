# Webhook 과제 – AI 활용 기록

## 1. 초기 설계 전략 수립

### Q1. X-Event-Id 기반으로 안전한 idempotency를 구현하려면?

#### Prompt : 

Account Change Webhook 처리 서버(Kotlin + SQLite3)를 설계하려고 한다.
요구사항은 다음과 같다:

- HMAC 서명검증 (X-Signature + WEBHOOK_SECRET)
- X-Event-Id 기반 멱등 처리
- 이벤트 상태 전이 (RECEIVED → PROCESSING → DONE/FAILED)
- 이벤트 수신 시 DB에 먼저 저장 (Inbox 패턴)
- 3개 이벤트 타입 처리
- 조회 API 2개 + webhook 수신 API 1개
- 테스트 시나리오 5개, 커버리지 80% 이상
- HTTP/DB/비즈니스 로직 계층 분리

요청:
- 기능 요구사항 / 비기능 요구사항 재정리
- 설계 시 반드시 결정해야 하는 항목들 리스트업 및 트레이드오프 분석
- DDD-lite 아키텍처 제안
- SQLite 스키마 초안 제안
- 상태 머신 정의
- 동시성 안전 패턴 제안
- 테스트 전략 매핑

**AI Response 요약**
1. 멱등성은 애플리케이션 로직이 아닌 DB 레벨에서 보장
    - event_id를 PRIMARY KEY로 설정
    - INSERT OR IGNORE 전략 사용

2. 상태 머신을 명확히 정의
    - RECEIVED → PROCESSING → DONE/FAILED
    - DONE/FAILED는 터미널 상태

3. 동시성 제어는 조건부 UPDATE로 처리
    - status='RECEIVED'일 때만 PROCESSING으로 변경
    - 변경된 row 수로 claim 성공 여부 판단

4. 동기 처리 방식 권장
    - SQLite 단일 writer 특성 고려
    - 큐 기반 비동기 구조는 현재 요구사항 초과

5. 테스트는 통합 중심으로 구성
    - HMAC 실패 시 DB write 없음 검증
    - 동일 eventId 재전송 시 DB 중복 없음 검증

### 실제 적용 내용

- events 테이블의 event_id를 PRIMARY KEY로 정의
- INSERT OR IGNORE + changes() 패턴 적용
- claimForProcessing() 메서드 도입
- 상태 전이를 EventStateMachine 클래스로 명시적 관리
- FAILED를 터미널 상태로 설계 (재시도 없음)
- 동기 처리 모델 채택


### Q2. Ktor에서 HMAC-SHA256 검증을 위해 raw body bytes를 안전하게 처리하려면?

Ktor 기반 Webhook 서버에서 다음 요구사항을 만족하는
HMAC-SHA256 검증 방식을 설계하고자 해 아래 질문에 대답해줘

요구사항:
- 헤더 형식: X-Signature: sha256=<lowercase_hex>
- WEBHOOK_SECRET 환경변수 사용
- 반드시 raw body bytes 기준으로 HMAC 계산 (재직렬화 금지)
- MessageDigest.isEqual()로 constant-time 비교
- 검증 실패 시 401 반환
- 검증 실패 시 DB 접근 금지
- 이후 JSON DTO 파싱 가능해야 함
- Body는 한 번만 읽도록 설계

질문:
- Ktor에서 raw body bytes를 안전하게 읽는 방법은?
- Body를 두 번 읽지 않는 구조는?
- HMAC → JSON 파싱 흐름은 어떻게 구성하는 것이 안전한가?
- HMAC 검증 로직은 어느 레이어에 두는 것이 적절한가?
- 흔히 발생하는 실수는 무엇인가?

### AI Response 요약
1. raw body는 `call.receive<ByteArray>()`로 단 한 번 읽는다.
2. 하나의 ByteArray를 HMAC 계산과 JSON 파싱에 모두 재사용한다.
3. HMAC 계산은 반드시 raw bytes 기준으로 수행한다.
4. 서명 비교는 `MessageDigest.isEqual()`을 사용한다.
5. 검증 → JSON 파싱 순서를 코드 구조로 강제한다 (early return).
6. HMAC 검증은 HTTP 레벨 관심사이므로 presentation 레이어에서 처리한다.
7. 재직렬화, 공백 차이, charset 혼용, String 비교는 모두 금지한다.

핵심 원칙:

receive<ByteArray>()
→ verifyHmac()
→ decodeToString()
→ decodeFromString<DTO>()
→ UseCase

---

### 실제 적용 내용

- WebhookController에서 `call.receive<ByteArray>()`로 raw bytes 수신
- SignatureVerifier 인터페이스를 infrastructure에 정의하고,
  Controller에서 호출하도록 설계
- HMAC 검증 실패 시 즉시 401 반환 후 함수 종료
- JSON 파싱은 검증 통과 이후에만 수행
- MessageDigest.isEqual() 사용
- DB/UseCase 진입은 검증 성공 이후에만 가능하도록 흐름 제어

---

### 설계 판단 근거

- HMAC은 HTTP 요청의 신뢰성을 판단하는 단계이므로
  Application/Domain으로 전달되기 전에 차단해야 한다.
- raw body 기준 계산을 강제함으로써
  JSON 재직렬화로 인한 서명 불일치 위험을 제거하였다.
- 검증 실패 시 조기 종료 구조를 통해
  DB 접근이 발생하지 않도록 안전성을 확보하였다.

### Q3. SQLite에서 트랜잭션 경계를 어디까지 잡고, 상태 전이 실패를 어떻게 처리해야 안전한가?

#### Prompt 요약
- 이벤트는 수신 즉시 DB(events inbox)에 먼저 저장해야 함
- 상태 전이: RECEIVED → PROCESSING → DONE/FAILED
- 멱등 키: X-Event-Id (event_id PRIMARY KEY)
- 중복 eventId 재전송 시 기존 상태/결과를 그대로 반환
- 처리 실패(비즈니스 오류)는 FAILED로 기록하되 HTTP 200으로 반환
- SQLite 특성(단일 writer, 락, WAL/busy_timeout)을 고려해 안정적으로 동작해야 함

질문:
1. 이벤트를 INSERT하는 시점부터 DONE/FAILED로 최종 상태를 기록하는 시점까지
   전부 하나의 트랜잭션으로 묶는 게 맞을까? 아니면 일부 단계는 분리하는 게 좋을까?

2. 동일한 X-Event-Id가 동시에 들어오는 상황에서 두 요청이 동시에 처리되지 않도록 안전하게 막으려면
   어떤 패턴이 가장 단순하고 안정적일까?(조건부 UPDATE로 claim하는 방식이 적절할까?)

3. 비즈니스 오류(예: 존재하지 않는 계정 UPDATE)와 DB 예외(락, SQL 오류 등)는 같은 방식으로 처리해도 될까?
   HTTP 응답과 상태 기록은 어떻게 구분하는 게 좋을까?

4. 혹시 PROCESSING 상태에서 예외가 발생하면 상태가 영원히 PROCESSING으로 남는 문제가 생길 수 있는데,
   이런 상황을 방지하려면 어떤 설계가 필요할까?

5. 동일 eventId가 재전송될 때, 현재 상태가 PROCESSING/DONE/FAILED 각각일 경우 
   어떤 HTTP 응답을 반환하는 게 가장 합리적일까?

코드는 최소화하고 설계/근거 중심으로 답변해줘.

### AI Response 요약

1. 트랜잭션은 3단계로 분리하는 것이 SQLite 환경에서 더 안전하다.

    - Tx1: INSERT (RECEIVED) → 수신 확인
    - Tx2: claim UPDATE (PROCESSING) → 단독 점유
    - 비즈니스 로직 실행 (트랜잭션 밖)
    - Tx3: UPDATE (DONE / FAILED) → 최종 결과 기록

   이유:
    - SQLite는 write lock을 트랜잭션 동안 점유하므로
      비즈니스 로직을 write 트랜잭션에 포함하면 lock 경합이 증가한다.
    - write lock 보유 시간을 최소화하는 것이 핵심이다.

2. 동시 중복 요청 차단은 조건부 UPDATE(claim 패턴)가 가장 단순하고 안전하다.

    - INSERT OR IGNORE → 중복 감지
    - UPDATE ... WHERE status='RECEIVED' → 단독 점유
    - affectedRows로 성공 여부 판단
    - SELECT 후 UPDATE 방식은 TOCTOU 문제로 금지

3. 비즈니스 오류와 인프라 오류는 다르게 처리해야 한다.

    - 비즈니스 오류 → FAILED 기록 + HTTP 200
    - DB/인프라 오류 → 500 반환 (상태 업데이트는 best-effort)

4. PROCESSING 고착 방지를 위해 이중 방어 전략을 권장한다.

    - 1차: finally 블록에서 FAILED 기록 시도
    - 2차: 일정 시간 초과 PROCESSING을 FAILED로 전환하는 Sweeper Job

5. 중복 요청 시 상태별 응답은 항상 200으로 반환한다.

    - DONE → 저장된 성공 결과 반환
    - FAILED → 저장된 실패 결과 반환
    - PROCESSING → 현재 처리 중 상태 반환
    - 절대로 재처리를 시작하지 않는다.

---

### 실제 적용 내용

- INSERT OR IGNORE + claim UPDATE 패턴 채택
- write 트랜잭션을 짧게 유지하도록 설계
- 비즈니스 예외는 FAILED로 기록 후 200 반환
- DB 예외는 500 반환
- 상태별 중복 응답 정책을 명시적으로 정의

---

### 설계 판단 근거

- SQLite 단일 writer 특성을 고려하여 write lock 점유 시간을 최소화하였다.
- 멱등성과 동시성 제어를 DB 레벨에서 단순한 패턴으로 해결하였다.
- 실패를 비즈니스 실패와 시스템 실패로 구분하여 책임 경계를 명확히 하였다.
- PROCESSING 고착 가능성을 고려하여 복구 전략까지 설계에 포함하였다.

### Q4. Webhook을 비동기 큐 기반으로 처리하지 않고, 동기 처리 모델을 선택한 이유는 무엇인가?
너가 추천해준건 동기 모델이잖아 근데 내가 생각하기엔 수신 후 즉시 처리하는 동기 모델 대신 메시지 큐를 두고 비동기로 처리하는
것도 괜찮을거 같은데 어떨거같아?

현재 요구사항에서는 SQLite를 사용하고 있고, 외부 API 호출이나 대규모 확장 요구는 없어.

1. 동기 처리 모델과 비동기 큐 모델의 장단점은 뭘까?
2. SQLite 환경에서는 어떤 모델이 더 적합할까?
3. 상태 머신 설계 측면에서 복잡도 차이는 어느 정도?
4. 현재 요구사항 범위에서 동기 모델이 합리적인 선택인지 판단 근거를 알고 싶다.

### AI Response 요약

1. **동기 모델**
    - 장점: 단순한 구조, 상태 전이가 request 생애 안에서 완결, 응답과 처리 결과가 직접 연결됨
    - 단점: 요청 처리 시간이 길어질 경우 HTTP 커넥션 점유

2. **비동기 큐 모델**
    - 장점: 수신과 처리 분리, 버스트 트래픽 흡수 가능
    - 단점: 상태 단계 증가(QUEUED 등), 재시도/Dead Letter 처리 필요, polling/콜백 필요

3. **SQLite 환경에서는 동기 모델이 더 적합**
    - SQLite는 write가 직렬화되므로 worker를 여러 개 두어도 병렬 처리 이점이 없음
    - 별도 큐를 도입해도 결국 SQLite write에서 병목 발생
    - events inbox 테이블 자체가 내구성 있는 큐 역할 수행

4. **요구사항과의 정합성**
    - FAILED도 HTTP 200으로 반환해야 하므로,
      처리 결과를 동일 request에서 반환하는 동기 모델이 자연스러움
    - 외부 API 호출, 대규모 확장 요구가 없어 비동기 구조는 과도함

---

### 실제 적용 내용

- Webhook 수신 후 동일 request 스코프 내에서 처리 완료
- 상태 전이: RECEIVED → PROCESSING → DONE/FAILED
- 별도 메시지 큐는 도입하지 않음
- inbox(events) 테이블을 내구성 있는 큐로 활용
- 확장 필요 시에만 비동기 모델 고려

---

### 설계 판단 근거

- SQLite 단일 writer 특성상 비동기 큐 도입 시 실질적 처리량 이득이 없음
- 상태 머신 단순성 유지
- 요구사항(FAILED도 200 반환)과 일관성 유지
- 현재 범위 내에서 복잡도 최소화가 합리적 선택


### Q5. 테스트를 어떻게 설계해야 커버리지 80%를 ‘의미 있게’ 달성했다고 볼 수 있을까?

현재 Webhook 서버는 다음 요소들을 포함한다:

- HMAC 검증 로직
- X-Event-Id 기반 멱등 처리
- 상태 전이 (RECEIVED → PROCESSING → DONE/FAILED)
- SQLite 기반 Repository
- 동기 처리 모델
- 조회 API 2개

요구사항에 "커버리지 80% 이상"이 명시되어 있는데, 단순히 라인 커버리지만 채우는 테스트는 의미가 없다고 생각한다.

궁금한 점은 다음과 같다:

1. 단위 테스트와 통합 테스트를 어떤 비율로 구성하는 것이 좋을까?
2. 반드시 검증해야 하는 핵심 시나리오는 무엇인가?
    - (예: 중복 eventId 재전송, HMAC 실패, 상태 전이 실패 등)
3. Repository 계층은 Mock으로 테스트하는 것이 좋을까,
   아니면 인메모리를 사용하는 것이 더 적절할까?
4. 커버리지 80%를 “의미 있게” 달성하려면
   어떤 분기(branch)와 예외 케이스를 반드시 포함해야 할까?
5. config/DTO/data class 같은 단순 코드도 테스트 대상에 포함해야 할까?

설계와 테스트 전략 중심으로 설명해줘.


### AI Response 요약

1. **단위 테스트 : 통합 테스트 = 7 : 3 권장**

    - 단위 테스트(약 70%):  
      HMAC, 상태 전이, 멱등 로직, UseCase 중심
    - 통합 테스트(약 30%):  
      Ktor + SQLite `:memory:` 기반 실제 HTTP 흐름 검증

2. **반드시 검증해야 할 핵심 시나리오**

    - 정상 서명 + 신규 eventId → DONE
    - 잘못된 서명 / 헤더 누락 → 401 + DB write 없음
    - 중복 eventId (DONE/PROCESSING/FAILED 상태별)
    - 비즈니스 오류 → FAILED + 200
    - DB 예외 → 500
    - accountId 빈 값 → 400
    - 존재하지 않는 계정 조회 → 404

3. **Repository 테스트 전략**

    - UseCase 단위 테스트는 Mock 대신 In-memory Fake 권장
      → 상태 전이를 자연스럽게 검증 가능
    - 통합 테스트는 SQLite `:memory:` 사용
      → UNIQUE 제약, INSERT OR IGNORE 동작 실제 확인

4. **의미 있는 80%의 기준은 ‘분기 커버리지’**

    - HMAC 정상/실패/헤더 누락/접두사 오류
    - 신규/중복 eventId 모든 상태 분기
    - claim 성공/실패
    - 비즈니스 오류 vs 인프라 예외
    - 조회 API 200/400/404

5. **테스트가 불필요한 코드**

    - 단순 data class, enum
    - 단순 wrapper DTO
    - config 설정 값 자체

   → 조건 분기나 변환 로직이 있는 경우에만 테스트

---

### 실제 적용 방향

- 핵심 분기를 중심으로 테스트 케이스 설계
- happy path 반복 대신 실패/예외 케이스 우선
- In-memory Fake + SQLite `:memory:` 병행 사용
- data class 테스트는 생략하여 유지 비용 최소화

---

### 설계 판단 근거

- 커버리지 수치가 아니라 “결함 발견 가치”를 기준으로 테스트 범위 결정
- 멱등/상태 전이/예외 처리 분기가 가장 높은 리스크 영역
- SQLite 특성은 실제 DB로 검증해야 의미 있음


## 2. 설계 확정 및 구현 전략

본 프로젝트는 설계 단계(Q1~Q5)를 통해 요구사항을 충족하는 핵심 원칙과 구현 방향을 확정하였다.  
구현 단계에서는 **요구사항 위반 가능성이 큰 핵심 로직(HMAC 검증, 멱등 처리, 상태 전이, 트랜잭션/예외 처리)**을 우선 확정하고, 반복적인 보일러플레이트(DTO/Query API/라우팅 등)는 템플릿 형태로 빠르게 생산하는 전략을 채택한다.


너는 Kotlin 2.2 + Ktor 3.x + SQLite(JDBC) 기반 Webhook 서버를 구현하는 개발자다.
나는 이미 설계를 확정했으니, 설계를 바꾸거나 다른 아키텍처를 제안하지 말고 "요청한 파일 코드만" 작성해
앞으로의 답변은 아래 내용을 준수하여 작성해줘

[확정된 설계 원칙]
1) 처리 모델: 동기 처리 (request 스코프 내에서 DONE/FAILED까지 확정)
2) 멱등: X-Event-Id = events.event_id PRIMARY KEY
    - INSERT OR IGNORE + changes()로 신규/중복 판단
    - 중복이면 재처리 금지, 기존 상태/결과 그대로 반환
3) 상태 머신: RECEIVED → PROCESSING → DONE/FAILED (DONE/FAILED 터미널)
    - claim 패턴: UPDATE ... WHERE status='RECEIVED' 로 PROCESSING 선점
4) HMAC 검증:
    - X-Signature: "sha256=<lowercase_hex>"
    - raw body bytes로 계산(재직렬화 금지)
    - MessageDigest.isEqual()로 constant-time 비교
    - 검증 실패 시 DB/UseCase 진입 금지(early return)
5) 응답 정책:
    - 정상/비즈니스 실패(DONE/FAILED)는 HTTP 200
    - HMAC 불일치 401
    - 헤더/JSON 오류 400
    - DB/인프라 오류 500
6) 레이어 의존: presentation → application → domain
    - presentation은 UseCase만 호출 (DB 직접 접근 금지)
    - application은 repository interface에만 의존
    - domain은 프레임워크/DB 의존 금지
7) 코드 품질:
    - 컴파일 가능(import 포함)
    - 불필요한 추상화/프레임워크 추가 금지
    - 요청한 범위 외 파일 생성 금지

[프로젝트 패키지 구조]
base package: com.webhook
- presentation/ (Controller, dto)
- application/ (UseCase)
- domain/ (model, service)
- infrastructure/ (persistence, security)
- config/ (AppConfig, Application wiring)

[이번 요청의 작업 범위]
- 각 파일에 대해 구현해야 하는 클래스/함수 시그니처:
    - <필수 시그니처/함수 목록>
- 참고: 이미 존재하는 타입(있다면):
    - <예: EventStatus, EventType, AccountStatus...>

[세부 요구사항]
- 엔드포인트/동작(해당 시):
    - <예: GET /accounts/{accountId} ...>
- 에러 매핑(해당 시):
    - <400/401/404/500 조건>
- 직렬화:
    - kotlinx.serialization 사용 여부 및 DTO 필드명

[출력 형식]
- 설명/해설 금지
- 파일별로 코드만 출력


### AI Response 요약

AI는 위 설계 원칙을 인지하였으며,
다음 메시지에서 구체적인 파일/시그니처/요구사항을 전달하면
해당 범위 내에서만 코드 구현을 진행하겠다고 응답함.

---

### 설계 의도

- 코드 생성 과정에서 아키텍처 변경을 방지
- 설계 단계(Q1~Q5)와 구현 단계의 일관성 유지
- 요구사항 위반 가능성 최소화
- AI를 "코드 생산 도구"로 제한하고, 설계 주도권은 유지


## 3. 코드 작성 및 리뷰 
너는 Kotlin 기반 Webhook 서버를 구현하는 개발자다.

설계는 이미 확정되었으며 변경 불가다.
프레임워크(Ktor), DB, SQLite 관련 코드 절대 포함하지 말 것.
domain 레이어만 작성해.

[패키지 경로]
src/main/kotlin/com/webhook/domain/

[작성 파일 목록]
1) model/EventStatus.kt
2) model/EventType.kt
3) model/AccountStatus.kt
4) model/Account.kt
5) model/WebhookEvent.kt
6) service/EventStateMachine.kt
7) service/InvalidStateTransitionException.kt

[요구사항]

EventStatus:
- RECEIVED
- PROCESSING
- DONE
- FAILED

EventType:
- ACCOUNT_CREATED
- ACCOUNT_UPDATED
- ACCOUNT_DELETED

AccountStatus:
- ACTIVE
- SUSPENDED
- CLOSED

Account:
- accountId: String
- name: String
- email: String
- status: AccountStatus
- createdAt: java.time.Instant
- updatedAt: java.time.Instant

WebhookEvent:
- eventId: String
- eventType: EventType
- accountId: String
- rawPayload: String
- status: EventStatus
- createdAt: Instant
- processedAt: Instant? (nullable)
- errorMessage: String? (nullable)

EventStateMachine:
상태 전이 규칙:
- RECEIVED → PROCESSING
- PROCESSING → DONE
- PROCESSING → FAILED
- DONE, FAILED는 터미널 상태

구현 요구:
- canTransition(from: EventStatus, to: EventStatus): Boolean
- requireTransition(from: EventStatus, to: EventStatus)
  → 허용되지 않으면 InvalidStateTransitionException 발생
- 전이 규칙은 내부 Map으로 관리

출력 규칙:
- 설명 없이 파일별 코드만 출력
- import 포함
- 컴파일 가능한 수준
- 요청 범위 외 파일 생성 금지