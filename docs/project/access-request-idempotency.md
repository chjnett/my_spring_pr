# 권한 요청 생성 멱등성 구현 결과

기준일: 2026-11-02

## 해결하려는 실패

클라이언트가 `POST /api/access-requests`의 응답을 받기 전에 연결이 끊기면 성공 여부를 알 수 없다. 그대로 재시도할 경우 같은 권한 요청이 여러 건 생성될 수 있다. 이번 구현은 `Idempotency-Key`를 요청자와 API 범위 안에서 한 번만 선점해 이 문제를 막는다.

## 요청 계약

```http
POST /api/access-requests
Authorization: Basic <base64(email:password)>
Idempotency-Key: create-auditor-request-once
Content-Type: application/json

{"targetUserId":2,"role":"AUDITOR","reason":"보안 이벤트 검토"}
```

| 상황 | 응답 |
| --- | --- |
| 처음 받은 키와 본문 | `201 Created` |
| 같은 요청자·키·본문 재시도 | 최초 `201` 응답, `Idempotency-Replayed: true` |
| 같은 요청자·키, 다른 본문 | `422 IDEMPOTENCY_KEY_REUSED` |
| 최초 요청이 아직 완료되지 않은 상태 | `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` |

## 구현 방식

키의 유일 범위는 `(user_id, request_scope, idempotency_key)`다. `request_scope`는 `POST:/api/access-requests`이므로 기존 직접 역할 부여 API의 같은 문자열 키와 충돌하지 않는다.

```text
요청 본문 → SHA-256 request_hash
                    │
                    v
INSERT idempotency_records ... ON CONFLICT DO NOTHING
          │ 1행                         │ 0행
          v                             v
RoleGrantRequest 생성          저장된 hash와 결과 확인
          │                             │
response_access_request_id 저장 ────────┘
```

V3 migration은 기존 `idempotency_records`에 `response_access_request_id`를 추가한다. 역할 부여와 권한 요청 생성은 같은 멱등성 선점 구조를 사용하되, `request_scope`와 응답 참조 컬럼으로 작업을 구분한다.

## 최초 응답 스냅샷

멱등성 재생 시 단순히 현재 권한 요청을 조회하면 문제가 있다. 최초 생성 후 요청이 승인되었다면 현재 상태는 `APPROVED`지만, 최초 `201` 응답은 `PENDING`이었다.

따라서 재생 응답은 저장된 요청의 불변 생성 정보와 다음 고정값으로 재구성한다.

- `status=PENDING`
- `reviewerId`, `decisionReason`, `reviewedAt=null`
- `version=0`
- `updatedAt=createdAt`

이 방식은 전체 JSON을 별도 컬럼에 복제하지 않으면서 최초 생성 응답을 유지한다. 생성 응답 필드가 변경되거나 이유·대상 역할의 수정 기능이 추가되면 JSON snapshot 저장 또는 응답 전용 테이블을 다시 검토해야 한다.

## 실제 PostgreSQL 검증

동일한 요청자·키·본문으로 20개 트랜잭션을 동시에 실행했다.

```text
ACCESS_REQUEST_IDEMPOTENCY_RESULT total=20 created=1 replayed=19 persisted=1 records=1
```

검증 결과:

- 실제 권한 요청 생성 1건
- 최초 처리 응답 1건, 재생 응답 19건
- 멱등성 레코드 1건
- 모든 응답의 권한 요청 ID가 동일
- 요청 승인 후 재시도해도 응답은 최초 `PENDING`, 실제 DB 상태는 `APPROVED`
- Flyway V1~V3와 Hibernate schema validation 통과

2026-11-02 최신 전체 회귀 결과는 `./gradlew test --rerun-tasks` 기준 **48개 테스트 통과, 실패 0개**다.

## 남은 운영 과제

- 멱등성 레코드 TTL과 정리 배치
- 오래된 `PROCESSING` 레코드의 실패 복구 정책
- `Idempotency-Key` 형식·길이 제한 강화
- API scope별 created/replayed/in-progress 메트릭 분리
- 서버가 응답을 저장한 직후 장애가 발생하는 시나리오 테스트
