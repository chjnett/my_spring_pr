# 2026-11-02 로컬 실동작 검증

## 결론

Docker Compose PostgreSQL과 실제 `bootRun` 애플리케이션을 연결한 상태에서 회원가입, DB 인증, 권한 요청 생성, 멱등 응답 재생, RBAC 차단, Transactional Outbox 발행과 HMAC 서명 저장까지 확인했다.

자동 테스트가 아닌 실행 중인 로컬 애플리케이션과 개발 DB를 사용한 smoke test다.

## 실행 환경

| 항목 | 결과 |
| --- | --- |
| 애플리케이션 | `localhost:8081`, Spring Boot `bootRun` |
| 상태 확인 | `GET /actuator/health` → `200`, `UP` |
| 데이터베이스 | Docker Compose PostgreSQL |
| Outbox Publisher | 활성화 |
| HMAC key ID | `local-v1` |

## 검증 흐름

### 1. 사용자 생성

- 요청자 ID: `5`
- 대상 사용자 ID: `6`
- 두 요청 모두 `POST /api/users`에서 `201 Created`

테스트 비밀번호 값은 문서에 기록하지 않는다.

### 2. 인증된 권한 요청 생성

요청자 계정의 HTTP Basic 인증으로 다음 요청을 생성했다.

```text
request ID: 1
requester ID: 5
target ID: 6
requested role: AUDITOR
status: PENDING
version: 0
```

응답은 `201 Created`였다. actor ID는 요청 본문이 아니라 인증 principal의 사용자 ID `5`로 기록됐다.

### 3. 같은 멱등성 키 재요청

동일한 `Idempotency-Key`와 본문으로 다시 요청했다.

```text
HTTP 201
Idempotency-Replayed: true
response request ID: 1
```

DB 확인 결과 `role_grant_requests`는 계속 1건이고 Outbox 이벤트도 추가되지 않았다.

### 4. MEMBER의 승인 시도

요청자 MEMBER 계정으로 `POST /api/access-requests/1/approve`를 호출한 결과는 `403 Forbidden`이었다. 차단된 요청으로 승인 상태나 감사 이벤트가 생성되지 않았다.

### 5. Outbox와 보안 이벤트 확인

```text
event type: ACCESS_REQUEST_CREATED
actor ID: 5
outbox status: PUBLISHED
integrity key ID: local-v1
signature length: 64
```

`audit_outbox.event_id`와 `security_events.event_id`는 동일했다. Publisher 실행 이후 대기 중인 Outbox는 없고 `PUBLISHED` 1건만 존재했다.

## 확인된 보장

- 실제 DB 사용자 credential을 통한 인증 성공
- 클라이언트가 actor ID를 조작할 수 없는 principal 기반 기록
- 같은 멱등성 키의 응답 재생과 이벤트 중복 방지
- 역할이 부족한 사용자의 승인 API 접근 차단
- 비즈니스 커밋 후 Outbox의 자동 발행
- 보안 이벤트에 HMAC key ID와 64자리 SHA-256 서명 저장

## 남은 수동 검증

관리자 비밀번호는 도구 출력이나 문서로 전달하지 않는다. 따라서 관리자 승인 성공 흐름은 관리자 자격 증명을 사용자가 로컬 터미널에서 안전하게 입력한 뒤 진행한다. 성공 시 아래 항목을 추가로 확인한다.

1. 요청 상태 `PENDING → APPROVED`
2. 대상 사용자 ID `6`의 `AUDITOR` 역할 1건 생성
3. `ACCESS_REQUEST_APPROVED` Outbox와 security event 생성
4. 생성·승인 이벤트 모두 HMAC 검증 `VALID`
