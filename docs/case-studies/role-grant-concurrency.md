# 같은 권한 요청 20건은 왜 19번 실패해야 했나

## 한 줄 결론

`RoleGrant`를 user 모듈과 분리된 authorization 모듈이 소유하게 하고, PostgreSQL의 `UNIQUE(user_id, role)` 제약으로 동일 권한의 동시 부여를 한 건으로 제한했다.

## P — Problem

관리자가 권한 부여 버튼을 연속 클릭하거나 네트워크가 요청을 재시도하면, 같은 사용자에게 같은 `ADMIN` 권한이 여러 번 저장될 수 있다. 중복 권한 레코드는 이후 권한 취소와 감사 로그의 기준을 모호하게 만든다.

## A — Analyze

| 선택지 | 장점 | 한계 | 선택 |
| --- | --- | --- | --- |
| `existsByUserIdAndRole()` 사전 검사 | 빠른 409 응답 | 조회와 저장 사이 경쟁 조건을 막지 못함 | 보조 수단 |
| JVM `synchronized` | 단일 프로세스에서는 단순 | 여러 인스턴스에서 무효 | 미선택 |
| PostgreSQL `UNIQUE(user_id, role)` | 모든 쓰기 경로의 최종 정합성 | 충돌을 HTTP 계약으로 번역해야 함 | 최종 방어선 |
| Idempotency-Key | 재시도에 같은 결과 제공 | DB 중복 방지를 대체하지 못함 | 구현·검증 완료 |

MSA 관점에서는 authorization이 `User` Entity를 직접 관계로 가지지 않고 `userId`만 저장한다. 사용자 존재 여부는 `UserExistencePort`로 확인한다. 지금은 JPA Adapter가 이 Port를 구현하지만, 나중에 user 서비스가 분리되면 HTTP/gRPC Adapter로 바꿀 수 있다.

## A — Action

1. `authorization` 모듈에 `RoleGrant`, `Role`, `RoleGrantRepository`, `RoleGrantService`, `RoleGrantController`를 추가했다.
2. `role_grants(user_id, role)`에 `uk_role_grant_user_role` UNIQUE 제약을 뒀다.
3. 중복 제약 위반(SQLState `23505`)은 `DuplicateRoleGrantException`으로 바꾸고, API에서 `409 DUPLICATE_ROLE_GRANT`로 응답한다.
4. Testcontainers PostgreSQL 16에서 20개 트랜잭션이 조회를 끝낸 뒤 동시에 저장하도록 barrier를 만들었다.

```bash
./gradlew test --tests "*RoleGrantConcurrencyPostgresTest" --no-daemon
```

## R — Result

| 측정 항목 | 결과 | 검증 방법 |
| --- | --- | --- |
| 동시 권한 요청 | 20건 | `CountDownLatch` barrier |
| 저장 성공 | 1건 | 테스트 Future 결과 |
| UNIQUE 충돌 | 19건 | 실패 요청 집계 |
| DB에 남은 `ADMIN` 권한 | 1건 | `countByUserIdAndRole()` |
| 같은 키 동시 재시도 | 최초 `201` 1건, 재전송 `201` 19건 | Testcontainers PostgreSQL 16 |

```text
ROLE_GRANT_CONCURRENCY_RESULT total=20 success=1 unique_conflicts=19 persisted=1
IDEMPOTENCY_CONCURRENCY_RESULT total=20 first_201=1 replayed_201=19 role_grants=1 idempotency_records=1
```

## 후속 딥다이브

같은 `Idempotency-Key`와 같은 본문은 최초 `201`을 재전송하고, 다른 키로 이미 존재하는 권한을 요청할 때만 `409 DUPLICATE_ROLE_GRANT`를 반환한다. 설계와 동시성 검증은 [Idempotency-Key 딥다이브](../deep-dives/idempotency-key.md)에 기록했다.
