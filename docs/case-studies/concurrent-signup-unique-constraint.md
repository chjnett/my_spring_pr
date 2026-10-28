# 동시 가입 20건은 왜 19번 실패해야 했나

## 한 줄 결론

동일 이메일 가입 요청을 PostgreSQL에서 동시에 20번 실행했을 때, 애플리케이션의 사전 조회만으로는 중복을 막을 수 없었고 DB `UNIQUE` 제약이 저장을 1건으로 고정했다. 충돌은 API에서 `409 DUPLICATE_EMAIL`로 번역한다.

## P — Problem

회원 가입 흐름에서 `existsByEmail()`로 중복을 확인한 뒤 저장하면 안전해 보인다. 하지만 두 요청이 같은 시점에 조회하면 둘 다 "없음"을 볼 수 있고, 둘 다 저장을 시도한다.

중복 사용자 데이터가 생기면 로그인 식별자, 권한 부여, 감사 로그가 모두 모호해진다. 이 문제는 요청이 한 건일 때가 아니라 동시에 들어올 때만 드러난다.

## A — Analyze

| 선택지 | 장점 | 한계 | 선택 |
| --- | --- | --- | --- |
| `existsByEmail()` 사전 검사만 사용 | 빠른 사용자 피드백 | 조회와 저장 사이의 경쟁 조건을 막지 못함 | 보조 수단 |
| Java `synchronized` | 단일 JVM에서 단순함 | 서버가 여러 대면 보호 범위가 사라짐 | 미선택 |
| PostgreSQL `UNIQUE(email)` | 모든 쓰기 경로에서 최종 정합성 보장 | 충돌 예외를 API 계약으로 번역해야 함 | 최종 방어선 |
| 멱등성 키 | 재시도 요청을 같은 결과로 처리 가능 | 가입 중복 자체의 DB 제약을 대체하지 못함 | 다음 단계 |

따라서 Service의 사전 검사는 읽기 쉬운 오류를 제공하고, `users.email`의 `UNIQUE` 제약은 동시성 상황의 최종 정합성을 지킨다.

## A — Action

1. `users.email`에 UNIQUE 제약을 뒀다.
2. 가입 로직은 사전 중복 확인 뒤 `saveAndFlush()`를 호출한다.
3. PostgreSQL SQLState `23505`와 제약 이름 `uk_user_email`을 확인해 `DuplicateEmailException`으로 변환한다.
4. 전역 예외 처리에서 `DuplicateEmailException`을 `409 DUPLICATE_EMAIL` 응답으로 변환한다.
5. Testcontainers PostgreSQL 16에서 20개 스레드가 조회를 끝낸 뒤 동시에 저장하도록 barrier를 만들었다.

```bash
./gradlew test --tests "*DuplicateEmailConcurrencyPostgresTest" --no-daemon
```

## R — Result

| 측정 항목 | 결과 | 검증 방법 |
| --- | --- | --- |
| 동시 가입 요청 | 20건 | `CountDownLatch` barrier |
| 저장 성공 | 1건 | 테스트 Future 결과 |
| UNIQUE 충돌 | 19건 | 예외 발생 요청 집계 |
| DB에 남은 사용자 | 1건 | `countByEmail()` |

테스트 출력:

```text
CONCURRENCY_RESULT total=20 success=1 unique_conflicts=19 persisted=1
```

## 한계와 다음 실험

이 실험은 Repository 직접 호출로 DB 제약의 역할을 검증한다. 다음 단계에서는 실제 `POST /api/users`를 동시에 호출해, 19개 충돌이 모두 `409 DUPLICATE_EMAIL` API 응답으로 번역되는지도 검증한다.
