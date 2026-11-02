# Transactional Outbox 감사 이벤트 구현 결과

## 결론

권한 요청 생성·승인·거절과 직접 역할 부여가 성공할 때 감사 이벤트를 같은 데이터베이스 트랜잭션의 `audit_outbox`에 저장하도록 구현했다. 별도 JDBC Publisher는 PostgreSQL의 `FOR UPDATE SKIP LOCKED`로 미발행 행을 선점하고, `security_events` 적재와 Outbox 완료 표시를 다시 하나의 트랜잭션으로 처리한다.

이 구조로 비즈니스 변경만 커밋되고 감사 로그가 사라지는 dual-write 문제를 피한다. 2026-11-02 실제 PostgreSQL 16 통합 테스트에서 동시 Publisher 2개가 20건을 처리한 결과는 `claimed=20, events=20, duplicates=0`이었다. 전체 회귀 테스트는 45개 모두 통과했다.

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

### Flyway V5

- `audit_outbox`: 발행 대기 이벤트와 `PENDING/PUBLISHED` 상태를 저장한다.
- `security_events`: 발행이 완료된 보안 이벤트를 저장한다.
- 두 테이블 모두 `event_id`에 UNIQUE 제약을 둔다.
- Outbox 조회는 `(status, id)`, 이벤트 검색은 `(event_type, occurred_at)`와 `(actor_id, occurred_at)` 인덱스를 사용한다.

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
./gradlew bootRun
```

H2 기반 테스트가 PostgreSQL 전용 SQL을 실행하지 않도록 기본값을 `false`로 두었다.

## 검증 결과

| 시나리오 | 결과 |
| --- | --- |
| 권한 요청 생성 후 승인 | `ACCESS_REQUEST_CREATED`, `ACCESS_REQUEST_APPROVED` 각 1건 생성 |
| Publisher 재실행 | 최초 2건, 다음 실행 0건; 최종 이벤트 2건 |
| 승인 중 역할 중복 오류 | 요청 상태 `PENDING` 유지, 승인 Outbox 0건 |
| Publisher 2개 동시 실행 | 대기 20건, 선점 합계 20건, 이벤트 20건, 중복 0건 |
| 전체 회귀 | 45 tests, 0 failures, 0 errors |

검증 클래스는 `TransactionalOutboxPostgresTest`이며 Testcontainers PostgreSQL 16에서 실행한다.

## 현재 보안 한계와 다음 작업

현재 `security_events`는 애플리케이션 코드에서 UPDATE/DELETE 경로를 제공하지 않지만 DB 권한 차원의 append-only 강제는 아직 없다. 다음 단계에서 아래 항목을 이어서 구현한다.

1. 이벤트 canonical payload와 HMAC 서명·검증
2. 쓰기 전용 DB role 또는 UPDATE/DELETE 차단 정책
3. 실패 횟수, `FAILED` 상태, poison event 격리와 DLQ
4. Outbox backlog·최고 지연·발행 성공/실패 메트릭
5. Correlation ID 전파와 구조화 로그

따라서 이번 결과를 “완전한 위변조 방지 감사 저장소”라고 표현하지 않는다. 현재 보장 범위는 비즈니스 변경과 Outbox 생성의 원자성, 발행 재시도, 동시 Publisher 중복 방지다.
