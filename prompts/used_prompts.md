# # Webhook 과제 – AI 활용 기록

## 1. 초기 설계 전략 수립

### Q1. X-Event-Id 기반으로 안전한 idempotency를 구현하려면?

#### Prompt 요약
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

---
### Q2. Ktor에서 HMAC-SHA256 검증을 위해 raw body bytes를 안전하게 처리하려면?
#### Prompt 요약

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

### 실제 적용 내용

- WebhookController에서 `call.receive<ByteArray>()`로 raw bytes 수신
- SignatureVerifier 인터페이스를 infrastructure에 정의하고,
  Controller에서 호출하도록 설계
- HMAC 검증 실패 시 즉시 401 반환 후 함수 종료
- JSON 파싱은 검증 통과 이후에만 수행
- MessageDigest.isEqual() 사용
- DB/UseCase 진입은 검증 성공 이후에만 가능하도록 흐름 제어

### 설계 판단 근거

- HMAC은 HTTP 요청의 신뢰성을 판단하는 단계이므로
  Application/Domain으로 전달되기 전에 차단해야 한다.
- raw body 기준 계산을 강제함으로써
  JSON 재직렬화로 인한 서명 불일치 위험을 제거하였다.
- 검증 실패 시 조기 종료 구조를 통해
  DB 접근이 발생하지 않도록 안전성을 확보하였다.

---
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

#### AI Response 요약

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

#### 실제 적용 내용

- INSERT OR IGNORE + claim UPDATE 패턴 채택
- write 트랜잭션을 짧게 유지하도록 설계
- 비즈니스 예외는 FAILED로 기록 후 200 반환
- DB 예외는 500 반환
- 상태별 중복 응답 정책을 명시적으로 정의

#### 설계 판단 근거

- SQLite 단일 writer 특성을 고려하여 write lock 점유 시간을 최소화하였다.
- 멱등성과 동시성 제어를 DB 레벨에서 단순한 패턴으로 해결하였다.
- 실패를 비즈니스 실패와 시스템 실패로 구분하여 책임 경계를 명확히 하였다.
- PROCESSING 고착 가능성을 고려하여 복구 전략까지 설계에 포함하였다.

---
### Q4. Webhook을 비동기 큐 기반으로 처리하지 않고, 동기 처리 모델을 선택한 이유는 무엇인가?
#### Prompt 요약
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

### 실제 적용 내용

- Webhook 수신 후 동일 request 스코프 내에서 처리 완료
- 상태 전이: RECEIVED → PROCESSING → DONE/FAILED
- 별도 메시지 큐는 도입하지 않음
- inbox(events) 테이블을 내구성 있는 큐로 활용
- 확장 필요 시에만 비동기 모델 고려

### 설계 판단 근거

- SQLite 단일 writer 특성상 비동기 큐 도입 시 실질적 처리량 이득이 없음
- 상태 머신 단순성 유지
- 요구사항(FAILED도 200 반환)과 일관성 유지
- 현재 범위 내에서 복잡도 최소화가 합리적 선택

---
### Q5. 테스트를 어떻게 설계해야 커버리지 80%를 ‘의미 있게’ 달성했다고 볼 수 있을까?
#### Prompt 요약

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

### 실제 적용 방향

- 핵심 분기를 중심으로 테스트 케이스 설계
- happy path 반복 대신 실패/예외 케이스 우선
- In-memory Fake + SQLite `:memory:` 병행 사용
- data class 테스트는 생략하여 유지 비용 최소화

### 설계 판단 근거

- 커버리지 수치가 아니라 “결함 발견 가치”를 기준으로 테스트 범위 결정
- 멱등/상태 전이/예외 처리 분기가 가장 높은 리스크 영역
- SQLite 특성은 실제 DB로 검증해야 의미 있음

---

## 2. 설계 확정 및 구현 전략

### Q6. 설계확정 및 구현전략
#### Prompt 요약
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

### 설계 의도

- 코드 생성 과정에서 아키텍처 변경을 방지
- 설계 단계(Q1~Q5)와 구현 단계의 일관성 유지
- 요구사항 위반 가능성 최소화
- AI를 "코드 생산 도구"로 제한하고, 설계 주도권은 유지

## 3. 코드 작성 및 리뷰
### Q7. 도메인 레이어 코드 작성요청

#### Prompt 요약
설계 확정 이후, 순수 비즈니스 규칙을 담는 domain 레이어를 먼저 구현하도록 요청하였다.

요구사항:

- base package: `com.webhook`
- 프레임워크/DB 의존성 금지
- java.time.Instant 사용
- 다음 파일 생성:
    - EventStatus
    - EventType
    - AccountStatus
    - Account
    - WebhookEvent
    - EventStateMachine
    - InvalidStateTransitionException
- 상태 전이 규칙:
    - RECEIVED → PROCESSING
    - PROCESSING → DONE / FAILED
    - DONE, FAILED는 터미널 상태

### AI Response 요약

1. **Enum 정의**
    - EventStatus: RECEIVED, PROCESSING, DONE, FAILED
    - EventType: ACCOUNT_CREATED, ACCOUNT_UPDATED, ACCOUNT_DELETED
    - AccountStatus: ACTIVE, SUSPENDED, DELETED

2. **도메인 모델 정의**
    - Account: 계정 상태를 표현하는 불변 데이터 클래스
    - WebhookEvent: 이벤트 저장 구조 (status, processedAt, errorMessage 포함)

3. **상태 머신 구현**
    - allowedTransitions Map 기반 전이 규칙 관리
    - canTransition(): 허용 여부 Boolean 반환
    - requireTransition(): 허용되지 않으면 InvalidStateTransitionException 발생
    - DONE/FAILED는 빈 Set으로 터미널 상태 처리

4. **프레임워크/DB 의존성 없음**
    - 순수 Kotlin 코드로 구현
    - 레이어 분리 원칙 준수

### 실제 적용 결과

- domain 레이어 완성
- 이후 application / infrastructure 레이어의 기준 타입 확정
- 상태 전이 규칙이 코드로 명시되어 설계와 구현 일치 보장
- 단위 테스트 작성이 가능한 구조 확보

### 설계 검증 포인트

- 상태 전이 규칙이 설계 문서와 동일한가?
- 터미널 상태(DONE/FAILED)에서 추가 전이가 차단되는가?
- 외부 의존성 없이 순수 비즈니스 로직만 포함되어 있는가?

---
### Q8. Application 레이어가 의존할 Repository/보안 인터페이스를 어떻게 정의할 것인가?
#### Prompt 요약
- base package: `com.webhook`
- infrastructure 레이어에 interface만 정의
- DB 구현체(Sqlite*)는 작성하지 않음
- application은 interface에만 의존하도록 설계
- 멱등/claim/상태 전이 전략을 반영한 메서드 시그니처 정의

### AI Response 요약
#### 1. EventRepository 정의

Webhook inbox 패턴과 멱등 처리 전략을 반영한 인터페이스 구성:

- `insertIfAbsent(event)`  
  → INSERT OR IGNORE 전략을 전제로 한 멱등 진입점  
  → true = 신규 이벤트, false = 중복

- `findByEventId(eventId)`  
  → 중복 요청 또는 조회 API 대응

- `claimForProcessing(eventId)`  
  → `status='RECEIVED'` 조건부 UPDATE를 위한 선점 메서드  
  → 동시성 제어 핵심 지점

- `updateStatus(...)`  
  → DONE/FAILED 전이 및 처리 결과 기록

설계에서 정의한 **멱등 + 상태 머신 + claim 패턴**이 인터페이스 수준에서 명확히 표현됨.

#### 2. AccountRepository 정의

계정 현재 상태를 관리하기 위한 최소 인터페이스 구성:

- `findById(accountId)`
- `upsert(account)`
- `deleteOrClose(accountId, deletedAt)`

deleteOrClose는 구현체 선택 자유(물리 삭제 또는 DELETED 전이)로 두어
DB 전략과 도메인 로직을 분리함.

#### 3. SignatureVerifier 정의

- `verify(rawBody: ByteArray, signatureHeader: String): Boolean`

설계 원칙에 따라:
- raw body bytes 기준 HMAC 검증
- presentation에서 early return을 유도하는 boolean 반환 구조

### 설계 검증 포인트

- application 레이어가 DB/보안 구현체에 직접 의존하지 않도록 인터페이스 분리 완료
- 멱등 처리 전략(INSERT OR IGNORE + claim)이 메서드 시그니처에 명확히 반영됨
- HMAC 검증 책임이 infrastructure.security로 명확히 분리됨
- DDD-lite 의존 방향(presentation → application → domain) 유지 가능

### 구현 단계 영향

- 다음 단계에서 SqliteEventRepository 구현 시,
  멱등/claim SQL 로직을 안전하게 구현 가능
- WebhookProcessingUseCase가 이 인터페이스를 중심으로 오케스트레이션 가능

---
### Q9. Application 레이어가 의존할 Repository/보안 인터페이스를 어떻게 정의할 것인가?
#### Prompt 요약

- X-Signature 형식: `sha256=<hex>`
- 반드시 raw body bytes 기준으로 HMAC 계산
- 비교는 `MessageDigest.isEqual()` 사용
- WEBHOOK_SECRET 환경변수 사용
- 검증 실패 시 Boolean false 반환 (Controller에서 401 처리)
- 재직렬화 금지

### AI Response 요약

1. **환경변수 기반 Secret 로딩**
    - `WEBHOOK_SECRET`을 초기화 시점에 읽어 `ByteArray`로 변환
    - 환경변수 미설정 시 `IllegalStateException` 발생
      → 보안 설정 누락을 조기 탐지하도록 설계됨

2. **헤더 형식 검증**
    - `signatureHeader.isBlank()` → false
    - `"sha256="` prefix 검사
      → 요구된 헤더 형식 강제

3. **Hex 디코딩 구현**
    - `"sha256="` 제거 후 hex 문자열을 ByteArray로 변환
    - 길이 홀수 또는 잘못된 문자 존재 시 예외 → false 반환
      → 위조/잘못된 서명 입력 방어

4. **HMAC-SHA256 계산**
    - `Mac.getInstance("HmacSHA256")`
    - rawBody 그대로 `doFinal(rawBody)`
      → 재직렬화 없이 raw bytes 기준 계산 (설계 준수)

5. **상수 시간 비교**
    - `MessageDigest.isEqual(computed, expectedBytes)`
      → timing attack 방지 요구사항 충족

6. **레이어 분리 준수**
    - Ktor/DB 의존성 없음
    - infrastructure.security 책임에 맞는 순수 보안 구현


### 설계 검증 포인트

- raw body 기준 HMAC 계산 여부
- constant-time 비교 사용 여부
- 잘못된 헤더/hex 입력 방어
- 검증 실패 시 false 반환 → presentation 레이어에서 401 처리 가능

### 구현 단계 영향

- WebhookController에서 early return 구조 확정 가능
- HMAC 단위 테스트 작성 가능 (정상/위조/헤더 형식 오류 케이스)
- 보안 게이트가 완성되어 이후 DB 접근 전에 검증 보장

---
### Q10. Application 레이어가 의존할 Repository/보안 인터페이스를 어떻게 정의할 것인가?
#### Prompt 요약

- base package: `com.webhook`
- 순수 JDBC 기반 (Ktor 의존 없음)
- SQLite JDBC 드라이버 사용
- WAL 모드, FK 활성화, busy_timeout 설정
- accounts / events 테이블 생성
- 트랜잭션 헬퍼 제공
- 설계 원칙(멱등, 상태 머신, enum 정합성) 유지

### AI Response 요약

#### 1. connect() 구현

- `WEBHOOK_DB_PATH` 환경변수 사용 (없으면 `"webhook.db"`)
- `DriverManager.getConnection("jdbc:sqlite:$dbPath")`
- 연결 직후 PRAGMA 3종 적용:
    - `journal_mode=WAL`
    - `foreign_keys=ON`
    - `busy_timeout=5000`

→ SQLite 동시성/무결성 설정을 초기 연결 시점에 강제.

#### 2. initSchema() 구현

- `accounts` 테이블 먼저 생성 (FK 참조 대상)
- `events` 테이블 생성
    - event_id PRIMARY KEY
    - event_type CHECK 제약
    - status CHECK 제약
    - account_id FK 참조
- 인덱스 3개 생성:
    - idx_events_status
    - idx_events_account_id
    - idx_accounts_email

→ 설계 문서의 enum 값과 CHECK 제약이 정확히 일치하도록 반영됨.  
→ Inbox 패턴(events)과 현재 상태 테이블(accounts) 구조 확정.

#### 3. transaction(block) 구현

- autoCommit=false
- block(conn) 실행
- 성공 시 commit()
- 예외 발생 시 rollback() 후 재throw
- Connection은 use 블록으로 자동 close

→ Repository/UseCase에서 안전하게 트랜잭션 경계 제어 가능.

### 설계 검증 포인트

- WAL 모드 활성화 확인 가능 (`PRAGMA journal_mode;`)
- FK 제약 활성화 (`PRAGMA foreign_keys;`)
- 멱등 처리 기반 PK(event_id) 확정
- 상태/이벤트 타입 CHECK 제약으로 DB 레벨 방어
- 트랜잭션 실패 시 rollback 보장

### 구현 단계 영향

- SqliteEventRepository 구현 준비 완료
- UseCase에서 transaction 헬퍼 사용 가능
- IntegrationTest에서 실제 SQLite 동작 검증 가능
- DB 레벨 무결성 + 상태 제약 확보

### 검증 방법

1. `initSchema()` 호출 후 webhook.db 파일 생성 확인
2. SQLite CLI:
    - `.schema events`
    - `.schema accounts`
3. `PRAGMA journal_mode;` → `wal` 반환 확인
4. transaction 블록 내 예외 발생 시 데이터 미반영 확인


---
### Q11. SqliteEventRepository 구현 (멱등 + Claim 패턴)
#### Prompt 요약

- INSERT OR IGNORE + changes() 기반 멱등 처리
- 조건부 UPDATE(status='RECEIVED') 기반 claim 패턴
- Instant는 ISO-8601 TEXT 저장
- DatabaseFactory.transaction 사용
- 설계 변경 금지

### AI Response 요약

#### 1. insertIfAbsent 구현

- `INSERT OR IGNORE` 사용
- 직후 `SELECT changes()` 호출
    - 1 → 신규 이벤트
    - 0 → 중복 이벤트
- 이벤트의 created_at, processed_at은 `Instant.toString()` 저장

→ DB 레벨 멱등 처리 설계와 정확히 일치.

#### 2. findByEventId 구현

- SELECT 후 ResultSet → WebhookEvent 매핑
- EventType / EventStatus는 `valueOf()`로 enum 변환
- TEXT → Instant.parse()로 역변환

→ DB 저장 형식과 도메인 모델 정합성 유지.

#### 3. claimForProcessing 구현

- 조건부 UPDATE:
- UPDATE events
  SET status='PROCESSING'
  WHERE event_id=? AND status='RECEIVED'

- 직후 `SELECT changes()`로 선점 성공 여부 판단

→ 동시성 안전 패턴 정확히 반영.
→ 두 요청이 동시에 claim 성공하는 상황 차단.

#### 4. updateStatus 구현

- DONE/FAILED 전이 시 status, processed_at, error_message 업데이트
- transaction 블록 내 수행

→ 상태 머신 터미널 전이 기록 가능.

### 설계 검증 포인트

- 멱등성 DB 레벨 보장
- 상태 전이 claim 패턴 구현
- Instant ↔ TEXT 매핑 정확
- 트랜잭션 경계 DatabaseFactory에 위임
- 설계 원칙 위반 없음

### 현재 구현 수준

- Inbox 패턴 완성
- 멱등 처리 완성
- 동시성 제어 완성
- 상태 전이 기록 완성

---
### Q12. SqliteEventRepository 구현 (멱등 + Claim 패턴)
Application 레이어에서 WebhookProcessingUseCase를 구현하려 한다.

요구사항:

- 동기 처리 모델 (request 스코프 내 DONE/FAILED 확정)
- 멱등 처리: INSERT OR IGNORE + changes()
- claim 패턴: UPDATE ... WHERE status='RECEIVED'
- 중복 또는 claim 실패 시 기존 이벤트 조회 후 그대로 반환
- 비즈니스 오류(WebhookBusinessException)는 FAILED로 기록 후 200 반환
- SQLException 등 인프라 예외는 잡지 않고 상위로 전파 (Controller에서 500 처리)
- SQL/Ktor 직접 사용 금지
- Repository interface에만 의존

UseCase는 오케스트레이션만 수행하고, 도메인/DB 세부 구현에는 관여하지 않아야 한다.

### AI Response 요약

1. UseCase는 “얇은 오케스트레이터”로 구현
    - DB 직접 접근 없음
    - 상태 전이 로직 직접 구현하지 않음
    - repository 인터페이스만 호출

2. 처리 흐름

    1) WebhookEvent(status=RECEIVED) 생성
    2) insertIfAbsent
        - false → findByEventId → isDuplicate=true 반환
    3) claimForProcessing
        - false → findByEventId → isDuplicate=true 반환
    4) 이벤트 타입별 분기 처리
        - CREATED/UPDATED → accountRepository.upsert
        - DELETED → accountRepository.deleteOrClose
    5) 성공 시 DONE 상태로 update 후 반환

3. 예외 전략

    - WebhookBusinessException
      → FAILED 상태 기록
      → ProcessWebhookResult(status=FAILED) 반환 (HTTP 200)

    - SQLException / RuntimeException
      → catch하지 않음
      → 상위 레이어에서 500 처리

4. 멱등성과 동시성 보장

    - insertIfAbsent + claim 패턴으로 재처리 방지
    - 중복 요청은 기존 이벤트 상태 그대로 반환
    - 동일 eventId는 단 한 번만 비즈니스 로직 실행

5. 설계 원칙 준수

    - 동기 처리 모델 유지
    - 레이어 의존성 방향 준수 (application → repository interface)
    - 비즈니스 실패와 인프라 실패 명확히 구분

---
### Q13. SqliteEventRepository 구현 (멱등 + Claim 패턴)

#### Prompt 요약

Application 레이어(UseCase)에서 계정 처리를 위해 `AccountRepository`의 SQLite 구현체가 필요하다.  
Ktor/SQL 프레임워크 없이 JDBC로만 구현하며, `DatabaseFactory.transaction`을 사용한다.

요구사항:

- 파일 경로:  
  `src/main/kotlin/com/webhook/infrastructure/persistence/SqliteAccountRepository.kt`
- 구현 대상: `class SqliteAccountRepository : AccountRepository`
- `accounts` 테이블 스키마 정합성 유지:
    - `account_id` PK
    - `name`, `email` NOT NULL
    - `status` = ACTIVE/SUSPENDED/DELETED (TEXT)
    - `created_at`, `updated_at` TEXT (Instant.toString())
- **메서드 구현(필수)**
    1) `findById(accountId: String): Account?`
        - SELECT 후 ResultSet → `Account` 매핑
        - created_at/updated_at → Instant.parse()
    2) `upsert(accountId: String, rawPayload: String, updatedAt: Instant)`
        - rawPayload(JSON)에서 `payload.name`, `payload.email`, `payload.status` 추출
        - `INSERT ... ON CONFLICT(account_id) DO UPDATE` 방식으로 upsert
        - created_at은 최초 insert 시에만 설정되고 업데이트 시에는 유지
    3) `deleteOrClose(accountId: String, updatedAt: Instant)`
        - status를 DELETED로 업데이트
        - 영향 row가 0이면 `WebhookBusinessException("Account not found")` throw
- 파싱 정책:
    - `kotlinx.serialization.Json(ignoreUnknownKeys=true)` 사용
    - payload 누락/필수 필드 누락/잘못된 status는 `WebhookBusinessException` throw (비즈니스 오류)
- 출력 규칙:
    - 설명 없이 파일별 코드만 출력
    - import 포함, 컴파일 가능한 수준
    - 요청 범위 외 파일 생성 금지 / 설계 변경 제안 금지

### AI Response 요약

1. **JSON 파싱 계층 위임 구현**
    - `rawPayload` 전체 JSON에서 `payload` 객체를 추출하는 DTO를 내부에 정의
    - `ignoreUnknownKeys=true`로 파싱 안정성 확보
    - `payload.name/email/status` 누락 또는 비정상 값은 `WebhookBusinessException`으로 처리

2. **upsert 구현**
    - `INSERT INTO accounts (...) VALUES (...)`
      `ON CONFLICT(account_id) DO UPDATE SET ...` 패턴 적용
    - `updated_at`은 항상 갱신
    - `created_at`은 최초 insert 값이 유지되도록 설계(업데이트 구문에서 제외)

3. **deleteOrClose 구현**
    - `UPDATE accounts SET status='DELETED', updated_at=? WHERE account_id=?`
    - 업데이트된 row 수가 0이면 “계정 없음” 비즈니스 오류로 간주하여 `WebhookBusinessException` throw

4. **findById 구현(추가 요구 반영)**
    - `SELECT account_id, name, email, status, created_at, updated_at FROM accounts WHERE account_id=?`
    - 결과가 있으면 `Account`로 매핑하여 반환, 없으면 null 반환
    - status 문자열은 `AccountStatus.valueOf()`로 enum 매핑
    - timestamp는 `Instant.parse()`로 역변환

5. **설계 정합성**
    - DB 접근은 `DatabaseFactory.transaction` 내부에서만 수행
    - Ktor/UseCase/SQL 프레임워크 의존 없음
    - 비즈니스 오류(입력/도메인 규칙 위반)와 인프라 오류(DB 예외)를 구분할 수 있는 구조 확보

---
### Q13. Event 조회용 UseCase는 어떻게 설계하는 것이 적절한가?

#### Prompt 요약

Webhook 서버에서 `GET /events/{eventId}` 조회를 담당할 Application 레이어 UseCase를 구현한다.

요구사항:

- 파일 경로:  
  `src/main/kotlin/com/webhook/application/EventQueryUseCase.kt`
- SQL/Ktor 직접 사용 금지
- `EventRepository` 인터페이스에만 의존
- 반환 타입은 `sealed class EventQueryResult`로 정의
    - Success(WebhookEvent)
    - NotFound
    - Failure(message)
- 동작 규칙:
    - eventId가 blank → Failure("Invalid eventId")
    - repository.findByEventId()가 null → NotFound
    - 정상 조회 → Success(event)
    - DB/인프라 예외 → catch 후 Failure("Internal error")로 래핑 (예외 메시지 직접 노출 금지)
- 설명 없이 파일 코드만 출력

### AI Response 요약

1. sealed class 기반 결과 모델 정의
    - Success(WebhookEvent)
    - NotFound
    - Failure(String)
      → HTTP 레이어와 분리된 표현 모델 구성

2. 입력 검증
    - eventId가 blank이면 즉시 Failure("Invalid eventId") 반환
      → Controller에서 400으로 매핑 가능

3. Repository 조회
    - eventRepository.findByEventId(eventId) 호출
    - null이면 NotFound 반환
    - 존재하면 Success(event) 반환

4. 예외 처리 전략
    - Exception catch 후 Failure("Internal error") 반환
    - DB 예외 메시지를 직접 노출하지 않음 (보안 고려)

5. 설계 원칙 준수
    - Application 레이어에서 SQL/Ktor 의존 없음
    - Repository interface에만 의존
    - HTTP 상태코드와 분리된 결과 모델 사용

---
### Q14. Account 조회용 UseCase는 어떻게 설계하는 것이 적절한가?

#### Prompt 요약

Webhook 서버에서 `GET /accounts/{accountId}` 조회를 담당할 Application 레이어 UseCase를 구현한다.

요구사항:

- 파일 경로:  
  `src/main/kotlin/com/webhook/application/AccountQueryUseCase.kt`
- SQL/Ktor 직접 사용 금지
- `AccountRepository` 인터페이스에만 의존
- 반환 타입은 `sealed class AccountQueryResult`로 정의
    - Success(Account)
    - NotFound
    - Failure(message)
- 동작 규칙:
    - accountId가 blank → Failure("Invalid accountId")
    - repository.findById(accountId)가 null → NotFound
    - 정상 조회 → Success(account)
    - DB/인프라 예외 → catch 후 Failure("Internal error")로 래핑 (예외 메시지 직접 노출 금지)
- 설명 없이 파일 코드만 출력

### AI Response 요약

1. sealed class 기반 결과 모델 정의
    - Success(Account)
    - NotFound
    - Failure(String)
      → HTTP 상태코드와 분리된 표현 모델 구성

2. 입력 검증
    - accountId가 blank이면 즉시 Failure("Invalid accountId") 반환
      → Controller에서 400으로 매핑 가능

3. Repository 조회
    - accountRepository.findById(accountId) 호출
    - null이면 NotFound 반환
    - 존재하면 Success(account) 반환

4. 예외 처리 전략
    - Exception catch 후 Failure("Internal error") 반환
    - DB 예외 메시지 직접 노출 방지 (보안 고려)

5. 설계 원칙 준수
    - Application 레이어에서 SQL/Ktor 의존 없음
    - Repository interface에만 의존
    - 조회 책임만 수행하는 얇은 UseCase 구조 유지

---
### Q14.  API를 Ktor 기반 Presentation 레이어에서 구현하고, Application 레이어와 HTTP 응답분리

#### Prompt 요약

- Route 확장함수 형태로 구현
- GET /events/{eventId}
- DB 직접 접근 금지 (UseCase만 호출)
- UseCase 결과를 HTTP 상태코드로 매핑
    - Success → 200
    - NotFound → 404
    - Failure → 500
    - eventId blank → 400
- DTO 분리:
    - EventResponse
    - ErrorResponse

### AI Response 요약

1. Route 확장함수 정의
    - `fun Route.eventQueryRoutes(useCase: EventQueryUseCase)`
    - path parameter 추출

2. 입력 검증
    - eventId null/blank → 400 + ErrorResponse("Invalid eventId")

3. UseCase 결과 → HTTP 매핑
    - Success → 200 + EventResponse.from(event)
    - NotFound → 404 + ErrorResponse("Not found")
    - Failure → 500 + ErrorResponse("Internal error")

4. DTO 설계
    - EventResponse는 WebhookEvent를 API 응답 모델로 변환
    - enum은 `.name`
    - Instant는 `.toString()`

5. 레이어 분리 유지
    - Controller는 UseCase만 호출
    - Repository/DB 접근 없음


### 실제 적용 내용

- HTTP 계층은 상태코드 결정만 담당
- 비즈니스 로직은 Application 레이어에 위임
- 예외 메시지 직접 노출 방지
- 응답 DTO와 도메인 모델 분리 유지

---
### Q15. Account 조회 API를 Ktor 기반 Presentation 레이어에서 구현하고, Application 레이어와 HTTP 응답 분리

#### Prompt 요약

- Route 확장함수 형태로 구현
- GET `/accounts/{accountId}`
- DB 직접 접근 금지 (UseCase만 호출)
- UseCase 결과를 HTTP 상태코드로 매핑
    - Success → 200
    - NotFound → 404
    - Failure → 500
    - accountId blank → 400
- DTO 분리
    - `AccountResponse`
    - `ErrorResponse`
- 출력은 파일별 코드만 (설명 없이)

### AI Response 요약

1. **AccountResponse DTO 구현**
    - `@Serializable` data class로 정의
    - 필드: accountId, name, email, status, createdAt, updatedAt
    - `companion object.from(Account)` 제공
        - `status = account.status.name`
        - `createdAt/updatedAt = Instant.toString()`

2. **AccountQueryController 구현**
    - `fun Route.accountQueryRoutes(useCase: AccountQueryUseCase)` 형태
    - `GET /accounts/{accountId}` 라우트 정의
    - path parameter `accountId` 추출

3. **입력 검증**
    - `accountId`가 null 또는 blank →  
      `400 BadRequest + ErrorResponse("Invalid accountId")`

4. **UseCase 결과 → HTTP 매핑**
    - `Success` → 200 + `AccountResponse.from(result.account)`
    - `NotFound` → 404 + `ErrorResponse("Not found")`
    - `Failure` → 500 + `ErrorResponse("Internal error")`

5. **레이어 분리 유지**
    - Controller는 UseCase만 호출
    - Repository/DB 접근 없음
    - HTTP 상태코드 결정만 담당

6. **비고**
    - `ErrorResponse`는 import되어 사용되었으며,
      별도 DTO 파일로 구현 필요

### 실제 적용 내용

- Presentation 레이어는 HTTP 파라미터 검증과 상태코드 매핑만 수행
- 도메인 모델 `Account`를 직접 반환하지 않고 DTO로 변환
- 내부 예외 메시지 노출 없이 `"Internal error"` 고정 응답
- Application 레이어와 HTTP 응답 책임을 명확히 분리

---
### Q16. Webhook 수신 API (POST /webhooks/account-changes) 구현 및 HMAC 검증 처리

#### Prompt 요약

- Route 확장함수 형태로 구현
- POST `/webhooks/account-changes`
- raw body bytes는 단 1회만 읽기
- X-Event-Id, X-Signature 헤더 검증
- HMAC 검증 실패 시 DB/UseCase 진입 금지 (early return)
- JSON 파싱 실패 → 400
- 인프라 예외 → 500
- UseCase 호출 후 DONE/FAILED/중복 모두 200 반환
- DTO 분리:
    - WebhookRequest
    - WebhookResponse
    - ErrorResponse
- Controller는 UseCase만 호출 (Repository 접근 금지)


#### AI Response 요약

1. **Route 확장함수 구현**
    - `fun Route.webhookRoutes(useCase, verifier)`
    - `POST /webhooks/account-changes` 정의

2. **raw body 처리**
    - `call.receiveChannel().readRemaining().readBytes()`로 원본 바이트 1회 수신
    - raw bytes → HMAC 검증
    - raw bytes → String 변환 후 JSON 파싱

3. **헤더 검증**
    - `X-Event-Id` 누락 → 400
    - `X-Signature` 누락 → 401
    - 서명 불일치 → 401
    - 검증 실패 시 즉시 `return@post` (UseCase 호출 차단)

4. **JSON 파싱**
    - `Json { ignoreUnknownKeys = true }`
    - `SerializationException` → 400
    - `Instant.parse()` 실패 → 400
    - `EventType.valueOf()` 실패 → 400

5. **UseCase 호출**
    - `ProcessWebhookCommand` 생성
    - `useCase.process(cmd)` 호출
    - 반환 결과를 WebhookResponse로 매핑

6. **HTTP 응답 매핑**
    - 성공 / 실패 / 중복 모두 200
    - 예외 발생 시 500 + `"Internal error"`

7. **레이어 분리 유지**
    - Controller는 UseCase만 호출
    - DB 접근 없음
    - HMAC 검증은 Presentation 레이어에서 수행

#### 실제 적용 내용

- raw bytes 기반 HMAC 검증 구조 확정
- early return 패턴으로 보안 경계 명확화
- JSON 파싱 및 enum 변환 예외를 모두 400으로 처리
- 비즈니스 실패는 UseCase 결과로 전달 (HTTP 200 유지)
- 인프라 예외만 500으로 응답
- 멱등 처리 및 상태 머신은 Application 레이어에 위임

---
### Q17. AccountDomainService 구현 (계정 도메인 비즈니스 규칙 정의)

#### Prompt 요약

- 순수 도메인 계층 구현 (프레임워크/DB 의존 금지)
- 파일 경로: `domain/service/AccountDomainService.kt`
- EventType별 계정 상태 검증 로직 구현
- 위반 시 도메인 전용 예외 `AccountBusinessException` 발생
- 예외 메시지는 명확한 영문 문자열 사용

**비즈니스 규칙**

- ACCOUNT_CREATED
    - 기존 계정 존재 시 오류
- ACCOUNT_UPDATED
    - 기존 계정 없음 → 오류
    - 기존 계정이 DELETED → 오류
- ACCOUNT_DELETED
    - 기존 계정 없음 → 오류
    - 이미 DELETED → 오류

#### AI Response 요약

1. **도메인 전용 예외 정의**
    - `AccountBusinessException : RuntimeException`

2. **AccountDomainService 구현**
    - `fun validate(eventType: EventType, existing: Account?)`
    - `when(eventType)` 기반 분기 처리

3. **EventType별 규칙 반영**
    - CREATED → existing != null → "Account already exists"
    - UPDATED → existing == null → "Account not found"
    - UPDATED → status == DELETED → "Account is deleted"
    - DELETED → existing == null → "Account not found"
    - DELETED → status == DELETED → "Account is already deleted"

4. **레이어 규칙 준수**
    - Repository/DB 접근 없음
    - Ktor 의존 없음
    - 순수 도메인 로직만 포함

#### 실제 적용 내용

- 계정 상태 검증 책임을 Application 레이어에서 분리하여 도메인으로 이동
- 비즈니스 오류는 `AccountBusinessException`으로 명확히 구분
- deleted 계정에 대한 수정/삭제 방지 규칙 명시화
- EventType별 상태 검증 로직을 단일 서비스로 캡슐화

---
### Q18. AppConfig 구현 (환경변수 로딩 구조 정의)

#### Prompt 요약

- 순수 설정 로딩 전용 클래스 구현
- 파일 경로: `config/AppConfig.kt`
- 환경변수:
    - WEBHOOK_SECRET → 필수 (없으면 예외)
    - WEBHOOK_DB_PATH → 선택 (기본값 "webhook.db")
    - PORT → 선택 (기본값 8080)
- DB/Ktor 코드 포함 금지
- load() 정적 팩토리 메서드 형태로 구현

#### AI Response 요약

1. **AppConfig 데이터 클래스 정의**
    - `webhookSecret: String`
    - `dbPath: String`
    - `port: Int`

2. **환경변수 로딩 로직 구현**
    - `WEBHOOK_SECRET` 없으면 `IllegalStateException` 발생
    - `WEBHOOK_DB_PATH` 없으면 `"webhook.db"`
    - `PORT`는 `toIntOrNull()` 사용하여 파싱 실패 시 8080 기본값 적용

3. **구조 설계**
    - `companion object.load()` 팩토리 메서드 제공
    - 설정 로딩 책임을 단일 객체로 캡슐화
    - 외부 프레임워크 의존 없음

### 실제 적용 내용

- 애플리케이션 설정을 환경변수 기반으로 일원화
- 필수 설정 누락 시 애플리케이션 시작 단계에서 즉시 실패하도록 설계
- PORT 파싱 실패에 대한 방어 로직 추가
- DB 경로 및 시크릿 관리 책임을 명확히 분리

---
### Q19. Ktor Application 진입점 구현 및 수동 DI 와이어링

#### Prompt 요약

- 파일 경로: `config/Application.kt`
- Ktor `fun Application.module()` 구현
- 애플리케이션 시작 시 `DatabaseFactory.initSchema()` 1회 실행
- 수동 DI 와이어링
    - SqliteEventRepository
    - SqliteAccountRepository
    - HmacSignatureVerifier
    - WebhookProcessingUseCase
    - EventQueryUseCase
    - AccountQueryUseCase
- Routing 등록:
    - webhookRoutes
    - eventQueryRoutes
    - accountQueryRoutes
- ContentNegotiation(JSON) 설치
- StatusPages 전역 예외 처리
    - 모든 Throwable → 500 + ErrorResponse("Internal error")
- 설명 없이 코드만 출력

#### AI Response 요약

1. **초기화 로직**
    - `DatabaseFactory.initSchema()`를 module 시작 시점에 호출
    - 애플리케이션 구동 시 DB 스키마 자동 보장

2. **수동 DI 구성**
    - Repository → UseCase → Controller 순으로 객체 생성
    - 외부 DI 프레임워크 사용 없이 명시적 의존성 연결

3. **Ktor 플러그인 설치**
    - `ContentNegotiation` + `json()` 설정
    - `StatusPages` 설치
        - 모든 `Throwable`을 500으로 매핑
        - 내부 예외 메시지 노출 방지

4. **Routing 구성**
    - webhookRoutes(processUseCase, verifier)
    - eventQueryRoutes(eventQueryUseCase)
    - accountQueryRoutes(accountQueryUseCase)

5. **레이어 구조 유지**
    - Presentation → Application → Infrastructure 방향 유지
    - Controller는 UseCase만 참조
    - 예외는 전역에서 안전하게 처리

#### 실제 적용 내용

- 애플리케이션 시작 시 DB 스키마 자동 초기화 구조 확정
- DI 프레임워크 없이 명시적 의존성 주입으로 구조 단순화
- 전역 예외 처리로 인프라 오류 시 500 응답 보장
- JSON 직렬화 설정 일원화
- 모든 API 라우트를 단일 module()에서 통합 구성



## 4. 테스트 코드 작성

### Q20. EventStateMachine 단위 테스트 작성 (허용/비허용 전이 검증)

#### Prompt 요약

- 파일 경로: `src/test/kotlin/com/webhook/domain/EventStateMachineTest.kt`
- `EventStateMachine`에 대한 단위 테스트 작성
- `canTransition(from, to)` 검증 케이스
    - RECEIVED → PROCESSING = true
    - PROCESSING → DONE = true
    - PROCESSING → FAILED = true
    - DONE → PROCESSING = false
    - FAILED → PROCESSING = false
- `requireTransition(from, to)` 검증 케이스
    - 허용 전이: 예외 발생 없음
    - 비허용 전이: `InvalidStateTransitionException` 발생
- JUnit5 사용, assertTrue/assertFalse/assertThrows 사용
- 설명 없이 테스트 코드만 출력, import 포함, 컴파일 가능 수준

#### AI Response 요약

1. **canTransition 허용 전이 테스트 구성**
    - RECEIVED → PROCESSING: true
    - PROCESSING → DONE: true
    - PROCESSING → FAILED: true

2. **canTransition 비허용 전이 테스트 구성**
    - DONE → PROCESSING: false
    - FAILED → PROCESSING: false

3. **requireTransition 정상 케이스**
    - 허용된 전이에 대해 예외가 발생하지 않음을 확인
    - (RECEIVED→PROCESSING, PROCESSING→DONE, PROCESSING→FAILED)

4. **requireTransition 예외 케이스**
    - DONE → PROCESSING 시 `InvalidStateTransitionException` 발생
    - FAILED → PROCESSING 시 `InvalidStateTransitionException` 발생

5. **테스트 품질/구조**
    - JUnit5 `@Test` 기반
    - Assertions(assertTrue/assertFalse/assertThrows) 사용
    - 케이스가 작고 명확하게 분리되어 원인 추적이 쉬움

#### 실제 적용 내용

- 상태 머신 규칙을 테스트로 고정하여 리팩토링/확장 시 회귀 방지
- 허용/비허용 전이를 모두 커버하여 의미 있는 분기 커버리지 확보
- `requireTransition`의 예외 정책을 명확히 문서화(테스트로 표현)

---
### Q21. HmacSignatureVerifier 단위 테스트 작성

#### Prompt 요약

- HMAC-SHA256 기반 서명 검증 로직에 대한 단위 테스트 작성
- Header 형식: `sha256=<hex>`
- raw body ByteArray 기준 검증
- false 반환 정책 유지 (예외 throw 금지)
- 프로덕션 코드 수정 금지
- 설계 변경 금지
- JUnit5 기반 테스트
- WEBHOOK_SECRET 환경변수 기반 동일 알고리즘으로 테스트 내 HMAC 생성

#### AI Response 요약

1. 정상 서명 검증 테스트 작성
    - 동일 secret + 동일 payload 기준으로 생성한 HMAC은 true 반환

2. 서명 불일치 테스트 작성
    - 임의 hex 값 전달 시 false 반환 검증

3. prefix 누락 케이스 검증
    - `sha256=` 형식이 아닌 경우 false 반환 검증

4. 비정상 입력 케이스 검증
    - blank header
    - hex 형식 아님
    - 홀수 길이 hex  
      → 모두 false 반환 확인

5. HMAC 생성 방식
    - `Mac("HmacSHA256")`
    - `SecretKeySpec`
    - `%02x` 포맷으로 hex 생성
    - raw ByteArray 그대로 사용

#### 실제 적용 내용

- 경로: `src/test/kotlin/com/webhook/`
- 테스트 클래스: `HmacSignatureVerifierTest`
- 프로덕션 코드 수정 없이 검증 로직을 고정
- false 반환 정책을 테스트로 명확히 고정
- 예외 기반 설계로 변경되지 않도록 회귀 방지 장치 확보
- raw body 기반 검증 원칙을 테스트 수준에서 보장

이 테스트를 통해:

- HMAC 검증 정책 불변성 확보
- 보안 로직 회귀 방지
- 설계 원칙(동기 처리, false 반환 정책, 재직렬화 금지) 유지 보장

### Q22. WebhookProcessingUseCase 통합 테스트 파일 생성

#### Prompt 요약

- `WebhookProcessingUseCase`에 대한 단위 테스트 파일을 생성한다.
- 경로/패키지:
    - `src/test/kotlin/com/webhook/application/WebhookProcessingUseCaseTest.kt`
    - `package com.webhook.application`
- JUnit5 + MockK 기반, 실제 DB 사용 없이 Repository를 mock 처리한다.
- 설계 고정(절대 변경 금지):
    - 동기 처리(요청 스코프 내 DONE/FAILED 확정)
    - 멱등 처리(INSERT OR IGNORE + changes() 의미를 `insertIfAbsent()` 반환값으로 반영)
    - claim 패턴(`claimForProcessing(eventId)` 성공 시에만 처리 진행)
    - 상태 머신(RECEIVED → PROCESSING → DONE/FAILED, DONE/FAILED 터미널)
    - HMAC 검증은 Controller 책임(UseCase에서 하지 않음)
- 프로덕션 코드 수정 금지 / 설계 변경 제안 금지

#### AI Response 요약

1. 테스트 픽스처 구성
    - `EventRepository`, `AccountRepository`를 `mockk(relaxed = true)`로 준비
    - `WebhookProcessingUseCase(eventRepo, accountRepo)` 직접 생성
    - `ProcessWebhookCommand` 고정 입력(이벤트/계정/occurredAt/원문 rawPayload)

2. 신규 이벤트 정상 처리 테스트
    - `insertIfAbsent` = true, `claimForProcessing` = true 조건에서 `DONE`, `isDuplicate=false`
    - 호출 순서 `insertIfAbsent → claimForProcessing → markDone`를 `verifyOrder`로 고정

3. 중복 이벤트 테스트
    - `insertIfAbsent` = false면 `isDuplicate=true`
    - claim/계정조회/markDone/markFailed가 호출되지 않음을 `verify(exactly=0)`로 보장

4. claim 실패 테스트(재처리 방지)
    - `insertIfAbsent` = true라도 `claimForProcessing` = false이면 이후 로직이 실행되지 않음을 검증

5. 도메인 처리 실패(FAILED 확정) 테스트
    - `ACCOUNT_CREATED`인데 이미 계정이 존재하도록 `accountRepo.findById`를 세팅
    - 결과 `FAILED`, `errorMessage` 존재 확인
    - `markFailed(eventId, ...)`는 1회 호출, `markDone`은 호출되지 않음을 검증

6. Repository 예외 전파 테스트
    - `eventRepo.insertIfAbsent`에서 예외 발생 시 `assertThrows(RuntimeException)`으로 상위 전파를 검증

#### 실제 적용 내용

- `src/test/kotlin/com/webhook/application/WebhookProcessingUseCaseTest.kt`에 단위 테스트 스켈레톤을 생성해,
  UseCase 레벨에서 멱등/claim/상태 확정 규칙을 테스트로 고정했다.
- 특히 중복 이벤트에서 **비즈니스 로직 미실행**과,
  신규 처리에서 **markDone 호출 순서**를 명시적으로 검증하여 회귀를 방지한다.
- 실패 시나리오(도메인 실패/리포지토리 예외)를 분리해,
  FAILED 터미널 상태 확정과 예외 전파 정책을 테스트에서 고정했다.

### Q23. SqliteEventRepository 통합 테스트 작성

#### Prompt 요약

- `SqliteEventRepository`에 대한 통합 테스트 파일 생성
- 실제 SQLite(JDBC) 기반 동작 검증
- in-memory DB 사용하여 테스트 간 완전 독립 보장
- 설계 고정:
    - INSERT OR IGNORE 기반 멱등 처리
    - insertIfAbsent() 반환값으로 신규/중복 판별
    - claim 패턴: RECEIVED → PROCESSING (UPDATE 영향 행 기반)
    - 상태 머신: RECEIVED → PROCESSING → DONE/FAILED
    - DONE/FAILED는 터미널 상태
- 프로덕션 코드 수정 금지
- 설계 변경 제안 금지

#### AI Response 요약

1. 테스트 환경 구성
    - `DatabaseFactory.init("jdbc:sqlite::memory:")` 사용
    - `DatabaseFactory.initSchema()`로 매 테스트 스키마 초기화
    - 각 테스트마다 독립 DB 보장

2. insertIfAbsent 신규/중복 검증
    - 첫 insert는 true 반환 및 RECEIVED 상태 저장 확인
    - 동일 eventId 재삽입 시 false 반환
    - 기존 데이터(상태, rawPayload) 덮어쓰기 없음 검증

3. claimForProcessing 동작 검증
    - RECEIVED → PROCESSING 전이 시 true 반환
    - PROCESSING/DONE/FAILED 상태에서는 false 반환
    - 상태 불변성 확인

4. updateStatus 동작 검증
    - PROCESSING → DONE 전이 확인
    - PROCESSING → FAILED 전이 확인
    - FAILED 시 errorMessage 저장 검증

5. 최소 경합 시나리오 검증
    - 동일 eventId에 대해 claim 두 번 호출 시
        - 첫 번째 true
        - 두 번째 false
    - 단일 성공 보장 확인

#### 실제 적용 내용

- 파일 경로:
  `src/test/kotlin/com/webhook/infrastructure/persistence/SqliteEventRepositoryTest.kt`
- 실제 SQLite in-memory DB 기반으로 Repository 로직을 검증
- 멱등 전략(INSERT OR IGNORE)과 claim UPDATE 조건을 테스트로 고정
- 상태 머신 전이 및 터미널 상태 유지 정책을 DB 레벨에서 보장
- UseCase 이전 단계에서 데이터 정합성과 상태 전이를 회귀 불가능하게 고정

이 테스트를 통해:

- 이벤트 멱등 처리 정책 안정성 확보
- 상태 전이 로직 DB 반영 정확성 검증
- 동시성 경합 시 단일 claim 보장 확인
- Repository 계층의 설계 불변성 확보

---
### Q24. WebhookController Ktor 테스트 작성

#### Prompt 요약

- `POST /webhooks/account-changes` 컨트롤러의 HTTP 응답 정책을 테스트로 고정한다.
- 테스트 파일 생성 위치/패키지:
    - `src/test/kotlin/com/webhook/presentation/WebhookControllerTest.kt`
    - `package com.webhook.presentation`
- Ktor `testApplication` 기반으로 라우팅을 구동하고, `SignatureVerifier`와 `WebhookProcessingUseCase`는 mock 처리한다.
- 설계 고정(변경 금지):
    - HMAC 검증 실패 시 UseCase 미호출 + 401
    - 헤더 누락/JSON 오류/accountId blank는 400
    - DONE/FAILED/중복은 200
    - 인프라(RuntimeException 등)는 500 (StatusPages 매핑)
    - raw body 기반 검증 원칙 유지(Controller에서 수신한 body를 기준으로 verifier 호출)

#### AI Response 요약

1. 테스트 구동 앱 헬퍼 구성
    - `testApplication { application { ... } }` 내에서
        - `ContentNegotiation { json() }` 설치
        - `StatusPages`에 `Throwable → 500 + ErrorResponse("Internal error")` 매핑
        - `routing { webhookRoutes(useCase, verifier) }`로 실제 라우트 적용
    - `install`, `routing` 호출의 DSL receiver 충돌을 피하기 위해 `this.install(...)`, `this.routing(...)` 형태로 명시

2. 핵심 시나리오별 응답 코드 검증
    - HMAC 불일치(verifier=false) → 401, UseCase 미호출 검증
    - X-Signature 헤더 누락 → 400, UseCase 미호출 검증
    - JSON 파싱 오류(body="{") → 400, UseCase 미호출 검증
    - accountId blank → 400, UseCase 미호출 검증
    - 정상 처리(DONE) → 200, UseCase 1회 호출 검증
    - 처리 결과(FAILED) → 200, UseCase 1회 호출 검증
    - 중복(isDuplicate=true) → 200 반환 검증
    - UseCase 예외(RuntimeException) → 500 반환 검증

3. MockK 기반 호출 검증
    - `verify(exactly = 0/1) { useCase.process(any()) }`로 “검증 실패 시 진입 금지” 및 정상 케이스 호출 보장
    - verifier는 케이스별로 `every { verifier.verify(any(), any()) } returns true/false`로 제어

#### 실제 적용 내용

- 파일: `src/test/kotlin/com/webhook/presentation/WebhookControllerTest.kt`
- Controller 계층에서 다음 설계 원칙을 테스트로 고정했다:
    - HMAC 검증 실패/헤더 누락/요청 바디 오류/필드 검증 실패 시 UseCase 미진입
    - DONE/FAILED/중복 처리 결과에 대한 응답 코드(200) 유지
    - 예기치 못한 인프라 예외는 StatusPages를 통해 500으로 매핑
- 결과적으로 HTTP 매핑 정책이 리팩토링/의존성 변경에도 흔들리지 않도록 회귀 방지 장치가 추가됨

---
### Q25. IntegrationTest (Webhook E2E) 작성

#### Prompt 요약

- Ktor `testApplication` 기반으로 실제 라우팅을 구동해 통합 테스트를 작성한다.
- SQLite in-memory를 사용해 Repository까지 포함한 end-to-end 흐름을 검증한다.
- 설계 고정(변경 금지):
    - 동기 처리 (요청 스코프 내 DONE/FAILED 확정)
    - 멱등(INSERT OR IGNORE) + claim 패턴(UPDATE WHERE RECEIVED)
    - 상태 머신(RECEIVED → PROCESSING → DONE/FAILED, 터미널 유지)
    - HMAC: `X-Signature: sha256=<hex>` / raw body bytes / isEqual 비교
    - HMAC 불일치 시 UseCase/DB 진입 금지 + 401
    - DONE/FAILED/중복은 200, 리소스 없음은 404, 인프라 오류는 500

#### AI Response 요약

1. 테스트 애플리케이션 구성
    - `DatabaseFactory.init("jdbc:sqlite::memory:")` + `initSchema()`로 매 실행 시 독립 DB 구성
    - 실제 `SqliteEventRepository`, `SqliteAccountRepository`, `WebhookProcessingUseCase`, `HmacSignatureVerifier`를 연결
    - `ContentNegotiation(json)` + `StatusPages(Throwable→500)` 설치 후 라우팅 등록

2. 시나리오 기반 통합 검증
    - 정상 서명 POST → 200, 이후 이벤트 조회 GET → 200 및 terminal 상태 확인
    - 동일 eventId로 2회 POST → 모두 200, terminal 상태 유지(멱등 보장)
    - 서명 불일치 POST → 401, 이후 이벤트 조회 GET → 404(처리/저장 진입 금지 보장)

3. 환경 의존성 처리
    - `WEBHOOK_SECRET`이 없으면 Assumption으로 테스트 skip 처리하여 환경 설정 누락 시 false-negative 방지

#### 실제 적용 내용

- 파일: `src/test/kotlin/com/webhook/IntegrationTest.kt`
- HTTP 계층부터 SQLite까지 “실제 연결”을 통해,
  설계의 핵심(보안 진입 차단, 멱등, claim, 상태 확정, 응답 정책)을 통합 테스트로 고정했다.

#### Prompt 요약

- `SqliteEventRepositoryTest` 실행 중  
  `[SQLITE_CONSTRAINT_FOREIGNKEY] 외래 키 제약 조건 위반` 오류 발생
- 에러 위치:
    - `SqliteEventRepository.insertIfAbsent`
    - events 테이블 INSERT 시점
- 설계는 변경 금지:
    - events.account_id → accounts.account_id FOREIGN KEY 유지
    - FK 제거 금지
    - Repository 로직 변경 금지
- 원인 분석 및 테스트 수정 방향 도출이 목적

#### AI Response 요약

1. 원인 분석
    - events.account_id가 accounts.account_id를 참조하는 FK 구조
    - 테스트에서 events INSERT 전에 accounts 레코드가 존재하지 않음
    - FK 제약이 활성화된 상태에서 INSERT 시 제약 위반 발생

2. 해결 전략
    - 설계(FK) 유지
    - 프로덕션 코드 수정 없이 테스트에서 accounts 선삽입
    - `insertAccount(accountId)` 헬퍼 추가
    - 또는 `@BeforeEach`에서 기본 계정 생성

3. 설계 관점 정리
    - FK 제약은 데이터 정합성 보장 목적
    - Repository 테스트는 DB 스키마 제약을 존중해야 함
    - 단위 테스트라도 실제 DB 제약 조건을 고려해야 함

#### 실제 적용 내용

- `SqliteEventRepositoryTest`에서 이벤트 insert 전에 accounts 레코드를 선삽입하도록 수정
- FK 제약을 제거하지 않고 테스트를 설계에 맞게 조정
- 결과적으로:
    - 스키마 정합성 유지
    - Repository 동작 검증 신뢰도 향상
    - 설계 불변성 확보

이 이슈를 통해:

- 테스트는 설계를 우회하는 것이 아니라,
  설계를 충족하는 방향으로 작성되어야 함을 명확히 함
- SQLite FK 제약 조건이 실제로 동작하고 있음을 검증


이 프로젝트는 Kotlin 2.2 + Ktor 3.x + SQLite 기반 Webhook 서버다.
설계는 절대 변경하면 안 된다.

### Q27. EventType 확장 및 요구사항 기반 분기 로직 추가

기존 EventType에 다음 두 타입을 추가하라:

- EMAIL_FORWARDING_CHANGED
- APPLE_ACCOUNT_DELETED

단순히 기존 분기에 묶지 말고, 요구사항 의미를 살려서 분기 로직을 추가하라.


#### 요구사항 의미

1. EMAIL_FORWARDING_CHANGED
    - 계정의 이메일 정보를 갱신한다.
    - 일반 ACCOUNT_UPDATED와 구분되는 이메일 변경 전용 이벤트다.
    - accountRepository에 이메일 갱신 전용 메서드가 필요하다면 추가하라.

2. APPLE_ACCOUNT_DELETED
    - 계정 상태를 APPLE_DELETED로 변경한다.
    - ACCOUNT_DELETED와 구분되는 상위 계정 레벨 삭제 사건이다.
    - AccountStatus에 APPLE_DELETED 상태를 추가하라.
    - Repository에서 해당 상태로 변경하는 로직을 구현하라.

#### 수정 대상 파일을 모두 식별하라

1. EventType enum
2. AccountStatus enum (APPLE_DELETED 추가)
3. WebhookProcessingUseCase when 분기
4. AccountRepository 인터페이스
5. SqliteAccountRepository 구현
6. DatabaseFactory schema CHECK 제약
7. 관련 테스트 (UseCase / Repository / Controller / Integration)

각 파일에서 어떤 코드가 추가/수정되어야 하는지 구체적으로 설명하라.
리팩토링 제안은 하지 말고 요구사항 반영 범위만 제시하라.

### AI Response 요약

1. EventType에 EMAIL_FORWARDING_CHANGED, APPLE_ACCOUNT_DELETED 추가 필요
2. AccountStatus에 APPLE_DELETED 상태 추가 필요
3. UseCase에 분기 추가:
    - EMAIL_FORWARDING_CHANGED → email update 전용 메서드 호출
    - APPLE_ACCOUNT_DELETED → 상태 APPLE_DELETED로 변경
4. AccountRepository에 updateEmail / markAppleDeleted 메서드 추가 필요
5. SqliteAccountRepository에 UPDATE 쿼리 추가 필요
6. events / accounts 테이블 CHECK 제약에 새 enum 값 추가 필요
7. 관련 단위/통합 테스트에 신규 이벤트 타입 케이스 추가 필요
8. SqliteAccountRepository에 email갱신 메서드, email 상태 delete 변경 메서드 추가


## 5.통합/단위테스트 및 수정
### Q28. EventType 확장 및 명확화 (요구사항 반영)

#### Prompt 요약

기존 EventType:

- ACCOUNT_CREATED
- ACCOUNT_UPDATED
- ACCOUNT_DELETED

요구사항에 따라 다음 2개를 추가한다:

- EMAIL_FORWARDING_CHANGED
- APPLE_ACCOUNT_DELETED

또한 도메인 실패 정책을 명확히 하기 위해 WebhookBusinessException을 표준 비즈니스 예외로 사용하도록 지시한다.

설계는 절대 변경하지 않는다.

이 프로젝트는 Kotlin + Ktor + SQLite 기반 Webhook 서버다.  
설계는 절대 변경하면 안 된다.

[설계 고정 원칙]
- 동기 처리 모델 유지
- 멱등 처리 전략 유지 (INSERT OR IGNORE + claim 패턴)
- 상태 머신(RECEIVED → PROCESSING → DONE/FAILED) 변경 금지
- 응답 코드 정책 변경 금지
- HMAC 검증 방식 변경 금지
- 기존 이벤트 타입 동작 변경 금지

##### 작업 목표

EventType에 다음을 추가한다:

- EMAIL_FORWARDING_CHANGED
- APPLE_ACCOUNT_DELETED

요구사항 의미를 살려 다음을 구현한다:

1. EMAIL_FORWARDING_CHANGED
    - 계정 이메일 갱신 전용 로직 구현
    - AccountRepository에 updateEmailForwarding(...) 추가

2. APPLE_ACCOUNT_DELETED
    - 계정 상태를 APPLE_DELETED로 변경
    - AccountStatus에 APPLE_DELETED 추가
    - AccountRepository에 markAppleDeleted(...) 추가

##### 도메인 실패 정책

- 비즈니스 실패는 반드시 WebhookBusinessException으로 표현한다.
- Repository에서:
    - 계정이 존재하지 않거나
    - payload가 유효하지 않거나
    - 도메인 조건이 위반된 경우
      WebhookBusinessException을 throw 한다.
- UseCase는 WebhookBusinessException만 catch하여
  이벤트 상태를 FAILED로 확정한다.
- RuntimeException은 인프라 예외로 간주하고 상위로 전파한다.

#### 수정 대상

1. EventType enum
2. AccountStatus enum
3. WebhookProcessingUseCase 분기
4. AccountRepository 인터페이스
5. SqliteAccountRepository 구현
6. DatabaseFactory CHECK 제약 수정
7. 관련 테스트 추가

리팩토링 제안 및 설계 변경은 금지.

#### AI Response 요약

1. EventType에 EMAIL_FORWARDING_CHANGED, APPLE_ACCOUNT_DELETED 추가 필요
2. AccountStatus에 APPLE_DELETED 상태 추가 필요
3. WebhookProcessingUseCase의 when 분기에 2개 케이스 추가
4. AccountRepository에:
    - updateEmailForwarding(...)
    - markAppleDeleted(...)
      메서드 추가 필요
5. SqliteAccountRepository에 UPDATE 쿼리 구현 및 changes() 검증 로직 추가
6. DatabaseFactory.initSchema()의 CHECK 제약에:
    - 새 event_type 값 추가
    - accounts.status에 APPLE_DELETED 추가
7. WebhookBusinessException을 도메인 실패 표준 예외로 사용하도록 정책 고정
8. 관련 단위/통합 테스트에 신규 이벤트 타입 케이스 추가 필요

#### 실제 적용 의미

- 요구사항의 “이메일 변경”과 “상위 계정 삭제” 사건을 명확히 분리하여 도메인 모델 강화
- 단순 CRUD 이벤트 모델에서 이벤트 중심(Event-driven) 설계로 확장
- WebhookBusinessException을 통해 도메인 실패와 인프라 실패를 명확히 구분
- 기존 동기 처리/멱등 전략/상태 머신/응답 정책은 그대로 유지
- 이벤트 타입 확장이 시스템 전반에 미치는 영향 범위를 명확히 기록


### Q29. X-Signature 헤더 누락 시 400 vs 401 응답 정책 정리

#### Prompt 요약

- `X-Signature 헤더가 없으면 400 반환하고 useCase 미호출` 케이스 존재
- 현재 구현은 모든 서명 검증 실패를 401 Unauthorized로 처리
- REST 관점에서 400과 401 중 어떤 응답이 더 적절한지 판단 필요
- 설계 변경은 최소화해야 함

#### AI Response 요약

1. **400 Bad Request**
    - 필수 헤더 누락 등 요청 형식 자체가 잘못된 경우에 적절
    - 클라이언트 요청 스펙 위반에 해당

2. **401 Unauthorized**
    - 인증 정보가 없거나 유효하지 않은 경우에 사용
    - 서명 검증 실패(authentication failure)에 해당

3. REST 관점 결론
    - 헤더 자체가 없는 경우 → 400이 더 엄밀한 REST 해석
    - 헤더는 있으나 서명 값이 잘못된 경우 → 401이 적절

#### 최종 판단 (프로젝트 적용 기준)

- 본 프로젝트에서는 **보안 실패를 단일 정책(401 Unauthorized)으로 통일**하였다.
- 헤더 누락 또한 “유효한 인증 정보가 없는 상태”로 간주하여 401로 처리한다.
- 이는 보안 정책 단순화 및 일관성 유지 관점에서 합리적이다.
- 도메인 로직은 호출되지 않으며, DB 변경도 발생하지 않는다.


### Q30. 다음을 만족하는 **콘솔에서 바로 실행 가능한 curl request**를 만들어 달라.(검증용)

#### Prompt 요약
##### 1. 서명 성공 케이스 Request 생성 요청
“서명 성공 → 정상 처리 → useCase 1회 호출”을 검증하려 한다.
##### 2. 서명 실패(헤더 값 불일치) 케이스 Request 생성 요청
“서명 실패 → 401 + useCase 미호출”을 검증하려 한다.
##### 3. 서명 누락 케이스 Request 생성 요청
“서명 헤더 누락 → 401 + useCase 미호출”을 검증하려 한다.
##### 4. 동일 eventId 재전송(멱등) 케이스 Request 생성 요청
멱등 정책은 다음과 같다:
- `X-Event-Id` UNIQUE
- 이미 DONE → 200 + 이미 처리됨
- PROCESSING → 200 + 처리 중
- 도메인 로직은 1회만 실행

  를 검증하려 한다.
##### 5. EMAIL_FORWARDING_CHANGED 도메인 반영 확인 요청
`EMAIL_FORWARDING_CHANGED` 이벤트가 실제로 account의`email_forwarding` 값을 변경하는지 확인하려 한다.
##### 6. ACCOUNT_DELETED 상태 변경 확인 요청
`ACCOUNT_DELETED` 이벤트가 account 상태를`DELETED`로 변경하는지 확인하려 한다.
##### 7. 도메인 실패 → FAILED 저장 확인 요청
도메인 로직에서 예외가 발생했을 때:

- events.status = FAILED
- error_message 저장
- DONE 호출 금지

를 검증하려 한다.
##### 8. 도메인 실패 → FAILED 저장 확인 요청
JSON 파싱 실패 시 400 응답을 검증하려 한다.
##### 9. 필수 필드 누락 → 400 확인 요청
`account_id`가 blank 또는 누락된 경우 400 응답을 검증하려 한다.
##### 10. 상태 머신 전이 불가 테스트 요청
이벤트 상태 전이 불가 상황을 강제로 만들어FAILED 처리되는지 검증하려 한다.
##### 11. 추가적으로 검증 가능한 케이스 무엇이 있는지 확인요청
현재 설계의 틀 안에서 추가적으로 테스트 할만한 내용 있으면 확인해줘.


### AI Response 요약

#### 공통 환경 설정
```bash
export SECRET="dev-secret"
export BASE_URL="http://localhost:8080"

hmac_sha256_hex () {
  local body="$1"
  printf '%s' "$body" | openssl dgst -sha256 -hmac "$SECRET" -binary | xxd -p -c 256
}

sqlite3 ./webhook.db "INSERT OR IGNORE INTO accounts(account_id,name,email,status,created_at,updated_at
VALUES ('acc-001','tester','a1@example.com','ACTIVE','2026-03-01T00:00:00Z','2026-03-01T00:00:00Z');"
```
---
1. 서명 성공 → 정상 처리 → useCase 1회 호출
```bash
EVENT_ID="evt-success-$(uuidgen | tr '[:upper:]' '[:lower:]')"
NOW=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

BODY=$(cat <<JSON
{"eventType":"EMAIL_FORWARDING_CHANGED","accountId":"acc-001","payload":{"email":"new@example.com"},"occurredAt":"$NOW"}
JSON
)

SIG=$(hmac_sha256_hex "$BODY")

curl -i -X POST "$BASE_URL/webhooks/account-changes" \
  -H "X-Event-Id: $EVENT_ID" \
  -H "X-Signature: sha256=$SIG" \
  -H "Content-Type: application/json" \
  --data-binary "$BODY"
```
---
2. 서명 실패(잘못된 hex) → 401
```bash
EVENT_ID="evt-2-$(uuidgen | tr '[:upper:]' '[:lower:]')"

BODY=$(cat <<JSON
{"eventType":"ACCOUNT_DELETED","accountId":"acc-001","payload":{},"occurredAt":"$NOW"}
JSON
)
SIG="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

curl -i "$BASE_URL/webhooks/account-changes" \
-H "Content-Type: application/json" \
-H "X-Event-Id: $EVENT_ID" \
-H "X-Signature: sha256=$SIG" \
--data-binary "$BODY"
```

3. 서명 누락 → 401
```bash
EVENT_ID="evt-3-$(uuidgen | tr '[:upper:]' '[:lower:]')"
NOW=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

BODY=$(cat <<JSON
{"eventType":"EMAIL_FORWARDING_CHANGED","accountId":"acc_300","payload":{"email":"x@example.com"},"occurredAt":"$NOW"}
JSON
)

curl -i "$BASE_URL/webhooks/account-changes" \
-H "Content-Type: application/json" \
-H "X-Event-Id: $EVENT_ID" \
--data-binary "$BODY"
```

---

3. 동일 eventId 재전송(멱등)
```bash
EVENT_ID="evt-idem-$(uuidgen | tr '[:upper:]' '[:lower:]')"
NOW=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

BODY=$(cat <<JSON
{"eventType":"EMAIL_FORWARDING_CHANGED","accountId":"acc-001","payload":{"email":"idem@example.com"},"occurredAt":"$NOW"}
JSON
)

SIG=$(hmac_sha256_hex "$BODY")

echo "===== FIRST REQUEST (정상 처리 기대) ====="
curl -i -X POST "$BASE_URL/webhooks/account-changes" \
  -H "Content-Type: application/json" \
  -H "X-Event-Id: $EVENT_ID" \
  -H "X-Signature: sha256=$SIG" \
  --data-binary "$BODY"

echo
echo "===== SECOND REQUEST (duplicate 기대) ====="
curl -i -X POST "$BASE_URL/webhooks/account-changes" \
  -H "Content-Type: application/json" \
  -H "X-Event-Id: $EVENT_ID" \
  -H "X-Signature: sha256=$SIG" \
  --data-binary "$BODY"
```

---


5. EMAIL_FORWARDING_CHANGED 반영 확인
```bash
ACCOUNT_ID="acc-001"
EVENT_ID="evt-email-change-$(uuidgen | tr '[:upper:]' '[:lower:]')"
NOW=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
NEW_EMAIL="changed-$(date -u +%s)@example.com"

BODY=$(cat <<JSON
{"eventType":"EMAIL_FORWARDING_CHANGED","accountId":"$ACCOUNT_ID","payload":{"email":"$NEW_EMAIL"},"occurredAt":"$NOW"}
JSON
)

SIG=$(hmac_sha256_hex "$BODY")

echo "===== 1) 이벤트 전송 ====="
curl -i -X POST "$BASE_URL/webhooks/account-changes" \
  -H "Content-Type: application/json" \
  -H "X-Event-Id: $EVENT_ID" \
  -H "X-Signature: sha256=$SIG" \
  --data-binary "$BODY"

echo
echo "===== 2) DB에서 account.email 확인 ====="
sqlite3 ./webhook.db "select account_id, email from accounts where account_id='$ACCOUNT_ID';"
```

---

6. ACCOUNT_DELETED → status=DELETED 확인
```bash
ACCOUNT_ID="acc-001"
EVENT_ID="evt-account-del-$(uuidgen | tr '[:upper:]' '[:lower:]')"
NOW=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

BODY=$(cat <<JSON
{"eventType":"ACCOUNT_DELETED","accountId":"$ACCOUNT_ID","payload":{},"occurredAt":"$NOW"}
JSON
)

SIG=$(hmac_sha256_hex "$BODY")
echo "===== 1) 이벤트 전송 ====="
curl -i -X POST "$BASE_URL/webhooks/account-changes" \
  -H "Content-Type: application/json" \
  -H "X-Event-Id: $EVENT_ID" \
  -H "X-Signature: sha256=$SIG" \
  --data-binary "$BODY"

echo
echo "===== 2) DB에서 account.status 확인 ====="
sqlite3 ./webhook.db "select account_id, status from accounts where account_id='$ACCOUNT_ID';"
```

---

7. 도메인 실패 → FAILED + error_message 저장
```bash
ACCOUNT_ID="acc-001"
EVENT_ID="evt-fail-$(uuidgen | tr '[:upper:]' '[:lower:]')"
NOW=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

BODY=$(cat <<JSON
{"eventType":"EMAIL_FORWARDING_CHANGED","accountId":"$ACCOUNT_ID","payload":{"email":""},"occurredAt":"$NOW"}
JSON
)

SIG=$(hmac_sha256_hex "$BODY")

echo "===== 1) 실패 유도 이벤트 전송 ====="
curl -i -X POST "$BASE_URL/webhooks/account-changes" \
-H "Content-Type: application/json" \
-H "X-Event-Id: $EVENT_ID" \
-H "X-Signature: sha256=$SIG" \
-H "Content-Type: application/json" \
--data-binary "$BODY"
```

---

8. JSON 파싱 오류 → 400
```bash
EVENT_ID="evt-json-bad-$(uuidgen | tr '[:upper:]' '[:lower:]')"

# 깨진 JSON(닫는 } 없음)
BAD_BODY='{"eventType":"EMAIL_FORWARDING_CHANGED","accountId":"acc-001","payload":{"email":"broken@example.com"},"occurredAt":"2026-03-03T00:00:00Z"'

SIG=$(hmac_sha256_hex "$BAD_BODY")

curl -i -X POST "$BASE_URL/webhooks/account-changes" \
-H "Content-Type: application/json" \
-H "X-Event-Id: $EVENT_ID" \
-H "X-Signature: sha256=$SIG" \
--data-binary "$BAD_BODY"
```

---

9. account_id blank → 400
```bash
EVENT_ID="evt-account-blank-$(uuidgen | tr '[:upper:]' '[:lower:]')"
NOW=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

BODY=$(cat <<JSON
{"eventType":"EMAIL_FORWARDING_CHANGED","accountId":"","payload":{"email":"blank@example.com"},"occurredAt":"$NOW"}
JSON
)

SIG=$(hmac_sha256_hex "$BODY")

curl -i -X POST "$BASE_URL/webhooks/account-changes" \
-H "Content-Type: application/json" \
-H "X-Event-Id: $EVENT_ID" \
-H "X-Signature: sha256=$SIG" \
--data-binary "$BODY"
```

---

10. 상태 머신 전이 불가 → FAILED 처리 확인
```bash
    EVENT_ID="evt-10-$(uuidgen | tr '[:upper:]' '[:lower:]')"

BODY='{
"event_type": "ACCOUNT_DELETED",
"account_id": "acc_already_deleted",
"occurred_at": "2026-03-02T14:00:00Z",
"data": {}
}'

SIG=$(hmac_sha256_hex "$BODY")

curl -i "$BASE_URL/webhooks/account-changes" \
-H "Content-Type: application/json" \
-H "X-Event-Id: $EVENT_ID" \
-H "X-Signature: sha256=$SIG" \
--data-binary "$BODY"
```

---
현재 설계를 변경하지 않는 범위 내에서, 아래 3가지는 특히 “완성도 높은 구현”으로 평가받을 가능성이 높은 테스트들이다.

---
#### #11-1 동시성 멱등 레이스 테스트

단순히 “같은 X-Event-Id를 두 번 호출”하는 것을 넘어,  
**거의 동시에 2개의 요청을 보내는 레이스 상황**을 검증하는 테스트다.

검증 목적:

- 동일 `X-Event-Id`가 동시에 들어와도 도메인 로직은 정확히 1회만 실행되는지
- UNIQUE 제약 + 상태 전이 로직이 정상적으로 동작하는지
- 중복 요청이 500 에러가 아닌 정책 응답(200)으로 처리되는지
- 이벤트 상태가 PROCESSING 또는 DONE 규칙에 맞게 유지되는지

이 테스트는 실제 운영 환경에서 가장 빈번하게 발생할 수 있는 상황(네트워크 재시도, 외부 시스템 중복 전송 등)을 검증하는 것이다.

---

### 11-2. Raw Body 변경 시 서명 실패 검증 (재직렬화 금지 증명)

같은 의미의 JSON이라도 공백, 개행, 키 순서가 달라지면 raw bytes는 달라진다.  
서버가 진짜로 “raw body bytes 기준”으로 HMAC을 계산하고 있다면,  
포맷이 달라질 경우 서명도 반드시 달라져야 한다.

검증 방법:

- Pretty JSON으로 서명 생성 후 정상 요청 → 200
- 동일 데이터이지만 공백 제거(minified) 후 기존 서명 그대로 사용 → 401

이를 통해 다음을 증명할 수 있다:

- 서버가 JSON을 재직렬화하지 않는다
- HMAC이 정확히 raw body 기준으로 계산된다
- 보안 요구사항을 정확히 준수한다


---

### 11-3. FAILED 재전송 정책 고정 + error_message 검증

도메인 로직에서 예외가 발생해 이벤트가 FAILED 상태로 저장된 이후,  
동일 `X-Event-Id`로 재전송이 들어올 경우의 정책을 테스트로 명확히 고정하는 것이 중요하다.

검증 포인트:

- 첫 요청 실패 시:
    - events.status = FAILED
    - error_message가 NULL이 아니며 의미 있는 값 저장
    - DONE 상태로 변경되지 않음
- 동일 eventId 재전송 시:
    - 재처리 금지 정책이라면 도메인 로직 미호출
    - 재처리 허용 정책이라면 상태 전이 규칙에 맞게 처리

이 테스트의 핵심은 “정책을 명확히 정의하고 테스트로 고정했다”는 점이다.

특히 error_message 검증은 운영 관점에서 중요하다:

- 장애 원인 추적 가능
- 예외 타입이 유실되지 않음
- 빈 문자열이 아닌 의미 있는 메시지 저장


---

### Q31. ACCOUNT_CREATED 처리 시 FK 오류와 설계 방향에 대한 고민

#### Prompt 요약

ACCOUNT_CREATED 이벤트를 테스트하던 중 `foreign key constraint failed` 오류가 발생했다.  
현재 스키마에서 `events.account_id`는 `accounts.account_id`를 참조하고 있고,  
insertIfAbsent 단계에서 FK 오류가 발생하고 있다.

처리 흐름은 다음과 같다:

1. events에 먼저 INSERT (RECEIVED 상태)
2. 이후 accountRepository.upsert 수행

이 구조에서 계정이 아직 존재하지 않으면 FK 위반이 발생한다.

여기서 고민이 생겼다:

- FK를 제거해도 괜찮을까?
- account를 먼저 처리하는 방식으로 바꿔도 될까?
- 멱등성(X-Event-Id 기준 1회 처리 보장) 요구사항과 충돌하지는 않을까?
- 과제 스펙에서 ACCOUNT_CREATED/UPDATED는 필수가 아닌데, 굳이 지원해야 할까?

설계를 변경하지 않으면서 스펙을 만족하는 가장 안전한 방향이 무엇인지 정리가 필요하다.

---

#### AI Response 요약

1. FK 제거는 기술적으로 가능하지만 데이터 무결성이 깨질 수 있으며,
   과제에서 “설계 변경 금지” 조건에 위배될 가능성이 있다.

2. 현재 FK 구조에서는 accounts가 먼저 존재하지 않으면 events INSERT 자체가 불가능하다.

3. 그러나 과제 스펙을 다시 확인해보면 필수 이벤트는 다음 3가지뿐이다:

    - EMAIL_FORWARDING_CHANGED
    - ACCOUNT_DELETED
    - APPLE_ACCOUNT_DELETED

   ACCOUNT_CREATED / ACCOUNT_UPDATED는 필수가 아니다.

4. 따라서 가장 안전한 전략은:

    - 스펙 필수 3종만 정상 처리
    - 그 외 이벤트 타입은 저장 후 FAILED 처리 (Unsupported)
    - HTTP는 200 반환 (재전송 방지 및 빠른 응답 충족)
    - 이벤트는 반드시 저장하고 상태 관리 유지

5. 이렇게 하면:

    - 멱등성(X-Event-Id 기반 중복 처리) 유지
    - 상태 전이(RECEIVED → PROCESSING → DONE/FAILED) 유지
    - “저장 없이 바로 처리 금지” 조건 충족
    - FK 구조 유지 (설계 변경 없음)
    - ACCOUNT_CREATED로 인한 FK 문제 제거

---

### Q33. Unsupported 이벤트 처리 정책 고민

#### Prompt 요약

스펙에 없는 이벤트 타입이 들어왔을 때 어떻게 처리하는 것이 맞을까?

선택지:

1. Controller에서 400으로 차단하고 저장하지 않는다.
2. events에 저장한 후 FAILED로 처리하고 200을 반환한다.

과제 조건에는 “Webhook 요청은 저장되어야 한다”와
“빠르게 반환해야 한다”는 문구가 있다.

어떤 정책이 스펙에 더 부합하는가?

---

####  AI Response 요약

가장 스펙 친화적인 정책은:

- 서명/JSON 검증 통과한 요청은 반드시 저장
- 지원하지 않는 eventType은 FAILED 상태로 기록
- error_message에 Unsupported 사유 명시
- HTTP 200 반환

이 방식의 장점:

- 저장 + 상태 관리 요구 충족
- 재전송 폭탄 방지
- 멱등 정책 유지
- 감사/추적 가능
- 설계 변경 없음

---

#### 최종 설계 결정 정리

- FK 유지 (제거하지 않음)
- ACCOUNT_CREATED / ACCOUNT_UPDATED는 Unsupported 처리
- 필수 3종 이벤트만 도메인 처리
- 모든 유효 요청은 events에 저장
- 상태 전이 명확히 유지
- 멱등성은 insertIfAbsent + claimForProcessing 기반 유지

---

#### 설계적 의미

이 구조는 다음을 보장한다:

- 이벤트는 시스템에 “기록”으로 남는다.
- 처리 정책은 상태로 표현된다.
- 멱등성은 DB 레벨에서 제어된다.
- 데이터 무결성(FK)은 유지된다.
- 스펙을 벗어난 이벤트는 시스템 안정성을 해치지 않는다.

결론적으로,
설계를 변경하지 않고 과제 요구사항을 충족하는 가장 안전한 구조로 정리되었다.


}


### Q33. E2E 테스트 필요성 및 테스트 구조 정리

#### prompt 

현재 Webhook 과제(Kotlin + Ktor + SQLite)의 테스트 코드가 40개 이상으로 증가하면서 테스트 구조가 복잡해지고 가독성이 떨어지는 문제가 발생하였다.

과제 요구사항에는 다음과 같은 **필수 시나리오**가 명시되어 있다.

1. 서명 검증 성공/실패 케이스
2. 동일 eventId 재전송 시 중복 처리 방지(DB 유니크/로직)
3. EMAIL_FORWARDING_CHANGED 처리 후 계정 조회 값이 갱신됨
4. ACCOUNT_DELETED 처리 후 상태가 DELETED로 변경됨
5. 실패 케이스에서 FAILED 기록 및 error_message 저장됨

현재 단위 테스트, Repository 통합 테스트, 일부 통합 테스트가 혼재되어 있으며,  
E2E(HTTP → UseCase → DB → 조회 API까지 연결된 흐름) 테스트가 반드시 필요한지 판단이 필요하다.

---

##### 2. 상세 질문 (Prompt)

다음과 같은 사항을 명확히 정리하고자 한다.

1. 위 필수 시나리오를 “채점자가 신뢰할 수 있는 수준”으로 증명하려면 E2E 통합 테스트가 반드시 필요한지 알고 싶다.
2. 단위 테스트 / Repository 통합 테스트 / E2E 테스트가 각각 어떤 필수 시나리오를 커버하는지 명확하게 매핑해 달라.
3. 만약 E2E 테스트가 필요하다면, 최소 몇 개의 시나리오만으로도 제출 수준에서 충분한지 “최소 안전 패키지”를 제안해 달라.
4. 테스트 코드가 난잡해지지 않도록 레이어별로 어떤 기준으로 분리하는 것이 좋은지 가이드해 달라.
5. FAILED + error_message 저장 요구사항은 단위 테스트만으로 충분한지, 아니면 실제 DB까지 확인하는 테스트가 필요한지도 판단해 달라. 


#### AI Response

#### 3-1. E2E 테스트는 반드시 필요한가?

결론적으로, **최소 2~3개의 E2E 통합 테스트는 있는 편이 안전하다.**

단위 테스트만으로도 로직 자체는 충분히 검증할 수 있다.  
그러나 다음 항목은 HTTP 레벨과 DB 반영까지 연결된 흐름을 보여주는 것이 신뢰도를 높인다.

특히 아래 3개는 E2E가 있는 경우 완성도가 크게 올라간다.

- 서명 실패 시 401 반환 + DB/UseCase 진입 금지
- EMAIL_FORWARDING_CHANGED 이후 GET /accounts 값 실제 갱신 확인
- ACCOUNT_DELETED 이후 GET /accounts 상태가 DELETED로 반영 확인

---

#### 3-2. 테스트 종류별 커버 범위 매핑

#### 1) 단위 테스트 (Domain / UseCase / Verifier)

**커버 가능 항목**

- 멱등 로직 분기 (신규/중복/claim 실패)
- 상태 전이 (RECEIVED → PROCESSING → DONE/FAILED)
- 실패 시 FAILED 상태 전환 호출 여부
- error_message 전달 여부
- HMAC 계산 로직 자체 검증

**한계**

- HTTP 응답 코드(401/400/200)는 직접 검증 불가
- 실제 DB에 값이 반영되었는지는 확인 불가

---

#### 2) Repository 통합 테스트 (SQLite 실제 동작)

**커버 가능 항목**

- INSERT OR IGNORE 기반 DB 멱등성
- claim 패턴 동작 확인
- error_message 실제 컬럼 저장 여부
- CHECK 제약 및 FK 제약 검증

**한계**

- HTTP 응답 정책 검증 불가
- 전체 요청 흐름 검증 불가

---

#### 3) E2E 통합 테스트 (Ktor testApplication 기반)

**커버 가능 항목**

- 서명 성공/실패 → HTTP 코드 검증
- EMAIL_FORWARDING_CHANGED → POST 후 GET /accounts 값 변경 확인
- ACCOUNT_DELETED → POST 후 GET /accounts 상태=DELETED 확인
- 이벤트 조회 API를 통한 DONE/FAILED 상태 확인

**한계**

- 세밀한 분기 커버는 단위 테스트가 더 적합

---

#### 3-3. 최소 안전 E2E 패키지 제안

제출 안정성을 확보하려면 **3개 시나리오면 충분하다.**

##### E2E-1: 서명 성공 + EMAIL 변경 확인
- POST 성공 (200)
- GET /accounts 에서 이메일 변경 확인
- GET /inbox/events 에서 DONE 확인

→ 커버: 1번, 2번, 3번 일부

---

#### E2E-2: 서명 실패
- POST (잘못된 서명) → 401
- GET /inbox/events → 404 (저장 금지 정책 기준)

→ 커버: 1번 보안 요구사항

---

#### E2E-3: ACCOUNT_DELETED → DELETED 상태 확인
- POST 성공 (200)
- GET /accounts 에서 status=DELETED 확인
- GET /inbox/events 에서 DONE 확인

→ 커버: 4번

---

### 3-4. FAILED + error_message는 어떻게 검증하는 것이 적절한가?

FAILED 상태 전환 및 error_message 전달 여부는  
UseCase 단위 테스트 + Repository 통합 테스트 조합으로 충분히 강하게 검증 가능하다.

E2E까지 포함하면 더 좋지만, 시간 대비 효율은 낮다.  
제출 관점에서는 Repository 통합 테스트 1개로 실제 DB에 error_message가 저장되는지 확인하는 것으로 충분하다.

---

#### 3-5. 테스트 구조 정리 권장안

#### 1) domain/
- 상태 머신 단위 테스트

#### 2) application/
- UseCase 단위 테스트 (Mock 기반)

#### 3) infrastructure/persistence/
- SQLite 통합 테스트 (DB 제약/멱등/claim 확인)

#### 4) integration/ 또는 e2e/
- HTTP → DB 흐름 검증 (3개 내외 유지)

이 구조로 정리하면 테스트가 레이어별로 역할이 명확해지고,  
불필요하게 난잡해지는 것을 방지할 수 있다.

---

#### 4. 최종 결론

- 단위 테스트만으로도 로직 검증은 가능하다.
- 그러나 필수 시나리오를 명확히 증명하려면 최소 3개의 E2E 테스트가 있는 것이 안전하다.
- FAILED + error_message는 Repository 통합 테스트로 충분하다.
- 테스트는 레이어별 역할을 분리하여 관리해야 유지보수성이 확보된다.
- 과도한 E2E 테스트는 오히려 복잡성을 증가시키므로 최소 패키지 전략이 합리적이다.