# 권한 요청·승인 구현 결과

기준일: 2026-11-02

## 이번 구현 범위

직접 역할을 부여하는 기존 API 앞에 검토 가능한 업무 흐름을 추가했다.

```text
요청자 ── POST /api/access-requests ──> PENDING
                                           │
                           다른 검토자 ─────┼──── approve ──> APPROVED + RoleGrant
                                           └──── reject  ──> REJECTED
```

- `RoleGrantRequest`가 요청 이유, 대상 사용자, 역할, 요청자, 검토자와 상태를 보관한다.
- 상태 전이는 Entity 메서드 `approve`, `reject`만 사용한다.
- 요청자는 자신의 요청을 검토할 수 없다.
- `APPROVED` 또는 `REJECTED`인 요청은 다시 처리할 수 없다.
- 승인과 실제 `RoleGrant` 생성은 하나의 트랜잭션이다.
- Flyway V2가 `role_grant_requests` 테이블과 상태 조회 인덱스를 만든다.

## API 계약

요청자·검토자 ID는 Spring Security가 인증한 `SecureFlowPrincipal`에서만 읽는다. `MEMBER`는 요청을 생성할 수 있고, 승인·거절은 `ADMIN` 또는 `AUDITOR`만 가능하다. 상세 구현은 [Spring Security 인증·인가 구현 결과](authentication-and-authorization.md)에 기록한다.

| 기능 | API | 주요 응답 |
| --- | --- | --- |
| 요청 생성 | `POST /api/access-requests` + `Idempotency-Key` | `201`, 재생 헤더, 키 오용 `422` |
| 요청 목록 | `GET /api/access-requests?status=PENDING` | `200` |
| 요청 단건 | `GET /api/access-requests/{id}` | `200`, 없으면 `404` |
| 승인 | `POST /api/access-requests/{id}/approve` | `200`, 동시 수정·재처리 `409` |
| 거절 | `POST /api/access-requests/{id}/reject` | `200`, 자기 검토 `403` |

## 동시 승인 방어

`role_grant_requests.version`을 JPA `@Version`에 매핑했다. 서비스는 승인 상태로 바꾼 뒤 `saveAndFlush`를 호출하고, 그 다음 실제 역할을 부여한다.

```text
검토자 A: UPDATE ... WHERE id=? AND version=0  → 1행, version=1 → RoleGrant 생성
검토자 B: UPDATE ... WHERE id=? AND version=0  → 0행, 409       → 전체 롤백
```

역할을 먼저 저장하면 두 번째 요청의 충돌이 낙관적 락이 아니라 역할 UNIQUE 제약에서 발생할 수 있다. 요청 상태의 경쟁을 명시적으로 판별하기 위해 flush 순서를 고정했다.

승인 상태 변경과 역할 저장이 같은 트랜잭션이므로 역할 저장이 실패하면 요청도 `PENDING`으로 롤백된다. 요청만 승인되고 역할은 없는 중간 상태를 남기지 않는다.

## 실제 검증 결과

PostgreSQL 16 Testcontainers에서 두 트랜잭션이 같은 `version=0` 요청을 모두 읽은 뒤 동시에 승인하도록 실행했다.

```text
ACCESS_REQUEST_CONCURRENCY_RESULT approvals=2 success=1 conflicts=1 role_grants=1 version=1
```

검증된 내용:

- 동시 승인 2건 중 1건만 성공
- 충돌 1건은 낙관적 락 실패
- 최종 `RoleGrant`는 1건
- 요청의 최종 version은 1
- Flyway V1~V3가 빈 PostgreSQL에 순서대로 적용되고 Hibernate `validate` 통과

단위·웹 테스트는 생성, 승인, 거절, 자기 승인 금지, 종료 상태 재처리 금지, 입력 검증과 오류 코드를 확인한다.

현재 전체 회귀 결과는 `./gradlew test --rerun-tasks` 기준 **41개 테스트 통과, 실패 0개, 31초**다. DB 인증까지 반영된 Flyway 최신 버전은 V4다.

## 남은 Phase 1 작업

- 기존 직접 역할 부여 API를 내부 호환 경로로 제한하고 deprecated 문서화
- 목록 API 페이지네이션과 정렬 계약
- 승인·거절 감사 이벤트를 Transactional Outbox에 기록

권한 요청 생성 멱등성의 구조와 동시성 수치는 [권한 요청 생성 멱등성 구현 결과](access-request-idempotency.md)에 기록한다.
