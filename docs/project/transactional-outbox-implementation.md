# Transactional Outbox 감사 이벤트 구현 결과

## 결론

권한 요청 생성·승인·거절과 직접 역할 부여가 성공할 때 감사 이벤트를 같은 데이터베이스 트랜잭션의 `audit_outbox`에 저장하도록 구현했다. 별도 JDBC Publisher는 PostgreSQL의 `FOR UPDATE SKIP LOCKED`로 미발행 행을 선점하고, `security_events` 적재와 Outbox 완료 표시를 다시 하나의 트랜잭션으로 처리한다.

이 구조로 비즈니스 변경만 커밋되고 감사 로그가 사라지는 dual-write 문제를 피한다. 각 이벤트는 HMAC-SHA256으로 서명해 저장 후 payload 변조도 탐지한다. 2026-11-02 실제 PostgreSQL 16 통합 테스트에서 동시 Publisher 2개가 20건을 처리한 결과는 `claimed=20, events=20, duplicates=0`이었다. 전체 회귀 테스트는 51개 모두 통과했다.

## 해결하려는 실패

비즈니스 트랜잭션 안에서 역할을 저장한 뒤 외부 로그 저장소나 메시지 브로커를 호출하면 다음 두 실패 사이에 원자성을 보장하기 어렵다.

```text
역할 변경 COMMIT 성공 → 로그 전송 실패  = 권한은 바뀌었지만 감사 근거가 없음
로그 전송 성공 → 역할 변경 ROLLBACK     = 실제로 없던 변경의 감사 로그가 남음
```

SecureFlow는 먼저 같은 PostgreSQL 트랜잭션에 비즈니스 상태와 Outbox를 함께 저장한다.

```text
AccessRequestService @Transactional
    ├─ RoleGrantRequest / RoleGrant 변경 (JPA)
    └─ audit_outbox PENDING 저장 (JPA)
                  │ COMMIT
                  ▼
AuditOutboxPublisher @Transactional
    ├─ PENDING 행 선점 (JDBC, FOR UPDATE SKIP LOCKED)
    ├─ security_events INSERT (JDBC, event_id UNIQUE)
    └─ audit_outbox → PUBLISHED (JDBC)
                  │ COMMIT
                  ▼
             감사 이벤트 확정
```

어느 트랜잭션이든 커밋 전에 실패하면 해당 트랜잭션의 변경 전체가 롤백된다.

## 구현 범위

### Flyway V5~V7

- `audit_outbox`: 발행 대기 이벤트와 `PENDING/PUBLISHED` 상태를 저장한다.
- `security_events`: 발행이 완료된 보안 이벤트를 저장한다.
- 두 테이블 모두 `event_id`에 UNIQUE 제약을 둔다.
- Outbox 조회는 `(status, id)`, 이벤트 검색은 `(event_type, occurred_at)`와 `(actor_id, occurred_at)` 인덱스를 사용한다.
- V6는 두 테이블에 `integrity_key_id`, `integrity_signature`를 추가한다. 기존 V5 데이터와의 migration 호환성을 위해 nullable이며, 새 애플리케이션 이벤트는 항상 서명한다.
- V7은 `correlation_id`, `integrity_version`과 correlation 검색 인덱스를 추가한다. 기존 행은 nullable로 유지하고 새 이벤트는 HMAC v2를 사용한다.

### 기록되는 이벤트

| 이벤트 | Aggregate | 기록 시점 |
| --- | --- | --- |
| `ACCESS_REQUEST_CREATED` | `RoleGrantRequest` | 최초 멱등 요청으로 권한 요청이 생성될 때 |
| `ACCESS_REQUEST_APPROVED` | `RoleGrantRequest` | 승인과 역할 부여가 모두 성공할 때 |
| `ACCESS_REQUEST_REJECTED` | `RoleGrantRequest` | 거절 상태 전이가 성공할 때 |
| `ROLE_GRANTED` | `RoleGrant` | 직접 역할 부여가 최초로 성공할 때 |

멱등 요청의 응답 재생은 새 비즈니스 변경이 아니므로 Outbox 이벤트를 추가하지 않는다. actor ID는 요청 본문이 아니라 Spring Security principal에서 읽는다.

### JPA와 JDBC의 경계

- JPA는 권한 요청·역할 같은 비즈니스 Aggregate와 트랜잭션 안의 Outbox 생성에 사용한다.
- JDBC는 다수 Outbox 행의 잠금 선점과 보안 이벤트 적재에 사용한다.
- PostgreSQL JDBC 경계의 `timestamp with time zone`은 UTC `OffsetDateTime`으로 명시해 드라이버의 암시적 `Instant` 변환에 의존하지 않는다.

## 실패와 재시도 의미

Publisher는 선점, 이벤트 INSERT, 상태 UPDATE를 한 트랜잭션으로 실행한다.

1. 선점 후 프로세스가 종료되면 커넥션 종료와 함께 트랜잭션이 롤백되고 행 잠금이 풀린다.
2. 다음 실행이 같은 `PENDING` 행을 다시 선점한다.
3. `security_events.event_id` UNIQUE와 `ON CONFLICT DO NOTHING`이 재시도 중복을 막는다.
4. 여러 인스턴스는 이미 잠긴 행을 기다리지 않고 `SKIP LOCKED`로 다른 행을 처리한다.

Publisher는 기본적으로 꺼져 있다. 개발·운영 PostgreSQL에서 다음 환경 변수로 활성화한다.

```bash
OUTBOX_PUBLISHER_ENABLED=true \
OUTBOX_PUBLISHER_FIXED_DELAY=1000 \
AUDIT_HMAC_KEY_ID=production-v1 \
AUDIT_HMAC_SECRET='32자-이상의-별도-비밀값' \
./gradlew bootRun
```

H2 기반 테스트가 PostgreSQL 전용 SQL을 실행하지 않도록 기본값을 `false`로 두었다.

## HMAC 무결성

`AuditEventSigner`는 다음 필드를 UTF-8 바이트 길이와 값으로 연결한 canonical byte sequence에 HMAC-SHA256을 계산한다.

- `event_id`, `aggregate_type`, `aggregate_id`, `event_type`
- `actor_id`, JSON `payload`, UTC `occurred_at`
- HMAC v2에서는 요청 `correlation_id`

단순 구분자 연결 대신 `byteLength:value` 형식을 사용해 값 안에 구분자가 들어가도 필드 경계가 모호해지지 않는다. 검증 시에는 timing 차이를 줄이기 위해 `MessageDigest.isEqual`로 서명 바이트를 비교한다.

`AuditIntegrityVerifier`의 결과는 `VALID`, `INVALID`, `UNSIGNED`, `UNKNOWN_KEY`로 구분한다. 키 ID를 함께 저장하므로 어떤 키로 서명했는지는 알 수 있지만, 현재 구현은 실행 중인 단일 키만 검증한다. 실제 운영의 키 회전은 과거 키를 읽기 전용 key ring 또는 KMS에서 조회하도록 확장해야 한다.

V6까지 생성된 서명은 version이 없으면 v1로 검증한다. V7 이후 이벤트는 v2로 기록하며 correlation ID까지 서명한다. 알 수 없는 서명 규칙은 `UNKNOWN_VERSION`으로 구분한다.

## 요청 추적

`CorrelationIdFilter`는 요청의 `X-Correlation-ID`가 64자 이내의 안전한 영문·숫자·점·밑줄·하이픈 형식이면 유지하고, 아니면 UUID를 생성한다. 같은 값을 응답 헤더와 MDC에 설정하며 filter chain 종료 시 MDC를 반드시 정리한다.

`AuditEventRecorder`는 HTTP Thread의 MDC 값을 Outbox에 복사한다. Publisher Thread가 달라도 correlation ID가 이미 DB 행에 저장돼 있으므로 `security_events`까지 유지된다. HTTP 밖에서 Service를 직접 호출하면 recorder가 새 UUID를 만든다.

애플리케이션은 `AUDIT_HMAC_SECRET`이 없거나 32자 미만이면 시작을 거부한다. 테스트 전용 공개값은 `src/test/resources`에만 두며, 개발·배포 secret은 저장소에 커밋하지 않는다. 배포 환경에서는 Secret Manager/KMS에서 주입하고 DB 계정과 분리해야 한다. HMAC은 암호화가 아니므로 payload 기밀성을 제공하지 않는다.

## 검증 결과

| 시나리오 | 결과 |
| --- | --- |
| 권한 요청 생성 후 승인 | `ACCESS_REQUEST_CREATED`, `ACCESS_REQUEST_APPROVED` 각 1건 생성 |
| Publisher 재실행 | 최초 2건, 다음 실행 0건; 최종 이벤트 2건 |
| 승인 중 역할 중복 오류 | 요청 상태 `PENDING` 유지, 승인 Outbox 0건 |
| Publisher 2개 동시 실행 | 대기 20건, 선점 합계 20건, 이벤트 20건, 중복 0건 |
| 정상 이벤트 무결성 | HMAC 검증 `VALID` |
| DB에서 payload 직접 변조 | HMAC 검증 `INVALID` |
| 안전한 요청 ID | 응답 헤더·MDC·Outbox·security event에 동일하게 전파 |
| 잘못된 요청 ID | 로그에 넣지 않고 서버 UUID로 교체 |
| correlation ID 변조 | HMAC v2 검증 `INVALID` |
| 전체 회귀 | 54 tests, 0 failures, 0 errors, 0 skipped |

검증 클래스는 `TransactionalOutboxPostgresTest`이며 Testcontainers PostgreSQL 16에서 실행한다.

## 실패 격리·재시도 후속 구현

Flyway V8과 이벤트 단위 트랜잭션으로 poison event가 배치 전체를 막지 않도록 개선했다. 실패 이벤트는 지수 backoff로 재시도하고 최대 횟수 도달 시 `FAILED`와 `audit_outbox_dead_letter`로 격리한다. backlog·최고 지연·성공·실패·재시도·DLQ 지표와 Grafana 대시보드도 추가했다.

상세 설계와 강제 실패 검증은 [Outbox 실패 격리·재시도·Dead Letter 구현 결과](outbox-failure-isolation.md)에 정리했다.

## 현재 보안 한계와 다음 작업

Compose 운영 경로는 Flyway migrator와 application DB 계정을 분리한다. application 계정은 `security_events`에 `SELECT`, `INSERT`만 가지며 V12 migration이 `UPDATE`, `DELETE`를 회수한다. HMAC key ring은 활성 키로 신규 이벤트를 서명하고 과거 검증 키로 이전 이벤트를 계속 검증한다. 외부 수집 이벤트는 검색 컬럼까지 포함하는 HMAC v3을 사용하고 내부 outbox의 v2 이벤트도 계속 검증한다.

다음 항목은 여전히 운영 환경에서 보완해야 한다.

1. KMS/Secret Manager에 key ring을 연결하고 실제 회전 훈련 수행
2. 비동기 Executor와 외부 메시지 경계의 Correlation ID 전파
3. 운영자 승인·원인 기록을 포함하는 DLQ 재처리 명령
4. Outbox 최고 지연과 DLQ 발생 Prometheus alert rule

따라서 이번 결과를 “완전한 위변조 방지 감사 저장소”라고 표현하지 않는다. 현재 HMAC과 DB 최소 권한은 secret을 모르는 application 계정의 변경·삭제를 차단하고 서명 필드 변조를 탐지하지만, migrator 권한의 행 삭제, secret 탈취 후 재서명, 애플리케이션 자체의 악의적 기록은 막지 못한다. 현재 보장 범위는 원자성, 이벤트 단위 실패 격리, 제한된 재시도와 DLQ, 동시 Publisher 중복 방지, application 계정 append-only, 저장 후 필드 변조 탐지다.
