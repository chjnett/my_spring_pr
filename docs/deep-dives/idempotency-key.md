# Idempotency-Key: 재시도와 새로운 중복 요청을 구분하기

## 이 글에서 확인할 것

권한 부여 API에 같은 요청이 다시 들어왔을 때, `409 Conflict`를 줄 상황과 최초 `201 Created` 결과를 재전송할 상황을 구분한다.

## 문제의 출발점

`UNIQUE(user_id, role)`만으로 데이터 중복은 막을 수 있다. 하지만 네트워크에서 최초 응답만 유실되면 클라이언트는 서버가 처리에 성공했는지 알 수 없다.

```text
클라이언트 ── POST ADMIN ──> 서버: 권한 저장 성공
클라이언트 <── 201 Created ── 서버: 응답이 네트워크에서 유실
클라이언트 ── POST ADMIN 재시도 ──> 서버
```

이 재시도를 새 요청과 똑같이 취급해 `409 DUPLICATE_ROLE_GRANT`를 반환하면, 클라이언트는 “내 첫 요청이 성공했구나”를 알 수 없다.

## 두 제약은 역할이 다르다

| 대상 | 제약 | 막는 문제 |
| --- | --- | --- |
| `role_grants` | `UNIQUE(user_id, role)` | 새 요청이 동일 권한을 중복 생성하는 문제 |
| `idempotency_records` | `UNIQUE(user_id, request_scope, idempotency_key)` | 동일 작업의 네트워크 재시도가 다시 실행되는 문제 |

둘 중 하나로 다른 하나를 대체하지 않는다.

## SecureFlow의 요청 계약

```http
POST /api/users/10/role-grants
Idempotency-Key: grant-admin-once
Content-Type: application/json

{"role":"ADMIN"}
```

| 조건 | 결과 |
| --- | --- |
| 첫 요청 | `201 Created`, 권한 생성 |
| 같은 키·같은 본문 재시도 | 기존 `201 Created`, `Idempotency-Replayed: true` |
| 같은 키·다른 본문 | `422 IDEMPOTENCY_KEY_REUSED` |
| 다른 키·이미 존재하는 권한 | `409 DUPLICATE_ROLE_GRANT` |

현재 요청 본문이 `role` 하나뿐이므로 `SHA-256(role)`을 요청 해시로 저장한다. 실제 서비스에서는 JSON 정규화, 호출 주체, 테넌트, HTTP 경로까지 해시 범위와 키 범위를 명시해야 한다.

## 동시 요청을 선점하는 방법

`SELECT` 후 `INSERT`는 경쟁 조건을 만든다. SecureFlow는 PostgreSQL에서 다음 한 문장으로 최초 요청을 정한다.

```sql
insert into idempotency_records
    (user_id, request_scope, idempotency_key, request_hash, status, created_at)
values
    (?, ?, ?, ?, 'PROCESSING', current_timestamp)
on conflict (user_id, request_scope, idempotency_key) do nothing;
```

반환값이 `1`인 트랜잭션만 권한 부여를 실행한다. `0`인 요청은 완료된 기록을 읽어 저장된 결과를 재전송한다. 권한 생성과 응답 기록 완료는 같은 Spring 트랜잭션 안에서 커밋된다.

```text
동일 키 요청 A, B
        │
        ├─ A: INSERT ... ON CONFLICT → 1 → RoleGrant 생성 → COMPLETED → commit
        │
        └─ B: INSERT ... ON CONFLICT → 0 → 완료 레코드 조회 → 저장한 201 응답 재전송
```

## 검증 방법

Testcontainers PostgreSQL 16에서 같은 `Idempotency-Key`를 가진 요청 20개를 `CountDownLatch`로 동시에 시작했다.

```bash
./gradlew test --tests "*IdempotencyKeyConcurrencyPostgresTest" --no-daemon
```

```text
IDEMPOTENCY_CONCURRENCY_RESULT total=20 first_201=1 replayed_201=19 role_grants=1 idempotency_records=1
```

이 결과는 “메서드를 한 번만 호출했기 때문에 중복이 없었다”는 증명이 아니다. 서로 다른 20개 스레드와 실제 PostgreSQL 제약 아래에서, 최초 처리와 재전송을 구분한 결과다.

## 아직 남은 운영 설계

- `PROCESSING` 상태가 비정상 종료 뒤 오래 남았을 때의 만료·복구 정책
- 멱등성 키 보관 기간(TTL)과 삭제 정책
- 응답 전체를 저장할지, 현재처럼 재구성에 필요한 식별자만 저장할지
- 사용자 ID 대신 인증된 호출 주체·테넌트를 키 범위에 포함하는 방식

다음 단계에서는 이 기록의 보관 기간과 실패 상태를 설계한다.
