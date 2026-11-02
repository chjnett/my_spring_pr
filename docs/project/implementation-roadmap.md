# SecureFlow 포트폴리오 전환 로드맵

각 단계는 기능 개수보다 **실패를 재현하고 수치로 해결을 증명하는 것**을 완료 조건으로 삼는다. 다음 단계는 이전 단계의 테스트와 문서가 통과한 뒤 시작한다.

## Phase 0 — 제품 기준선 고정

### 구현

- 현재 사용자·권한·멱등성 테스트를 회귀 기준으로 유지한다.
- 패키지를 `identity`, `accesscontrol`, `audit`, `detection`, `alert`, `shared` 경계로 정리한다.
- Flyway를 추가하고 기존 테이블을 V1 migration으로 옮긴다.
- JPA 설정을 `ddl-auto=validate`로 전환한다.
- 테스트 프로필과 Testcontainers 생명주기를 정리해 종료 지연을 제거한다.

### 완료 증거

- 기존 24개 테스트 통과
- 빈 PostgreSQL에서 migration 후 애플리케이션 기동
- 모듈 의존 규칙을 검증하는 ArchUnit 테스트
- Testcontainers 종료 시 연결 재시도 경고 없음

## Phase 1 — 권한 요청·승인 제품 흐름

현재 상태: 핵심 상태 모델·API·동시 승인 방어·요청 생성 멱등성·Spring Security 인증/인가 구현 완료. 직접 부여 API 정리는 남아 있다.

### 구현

- `RoleGrantRequest`와 `@Version` 기반 상태 전이를 추가한다.
- 요청 생성, 목록 조회, 승인, 거절 API를 구현한다.
- 요청자와 승인자 분리, 종료 상태 재처리 금지, 멱등성 키를 적용한다.
- 기존 직접 권한 부여 API는 내부 호환 경로로 유지한 뒤 문서에서 deprecated 처리한다.

### 완료 증거

- 두 관리자의 동시 승인에서 1건 성공·1건 `409`
- 승인 성공 시 `RoleGrant` 한 건만 생성
- 상태 전이 단위·통합 테스트
- lost update 재현 전후 비교 문서

### 2026-11-02 구현 결과

- `RoleGrantRequest` 상태 모델, Flyway V2, 생성·조회·승인·거절 API 구현
- 자기 검토와 종료 상태 재처리 차단
- PostgreSQL 동시 승인 결과: 2건 중 1건 성공, 1건 충돌, 역할 1건, version 1
- PostgreSQL 동일 멱등성 키 20건: 생성 1건, 최초 응답 재생 19건
- DB BCrypt 인증과 RBAC: 미인증/오인증 `401`, MEMBER 승인 `403`, ADMIN 승인 `200`
- 상세 근거: [권한 요청·승인 구현 결과](access-request-implementation.md)
- 멱등성 근거: [권한 요청 생성 멱등성 구현 결과](access-request-idempotency.md)
- 인증·인가 근거: [Spring Security 인증·인가 구현 결과](authentication-and-authorization.md)

## Phase 2 — Transactional Outbox 감사 로그

현재 상태: 비즈니스 트랜잭션의 Outbox 기록, JDBC Publisher, 동시 선점, `event_id` 중복 방지, HMAC 변조 탐지, HTTP Correlation ID 전파, 이벤트 단위 실패 격리와 DLQ까지 구현했다. DB 권한 기반 append-only 강제와 키 회전은 남아 있다.

### 구현

- 권한 변경 트랜잭션에 `audit_outbox` 생성을 포함한다.
- JDBC `FOR UPDATE SKIP LOCKED` Publisher와 재시도·실패 격리를 구현한다.
- `security_events` append-only 저장과 HMAC 무결성 검증을 구현한다.
- Correlation ID 필터와 구조화 로그를 추가한다.

### 완료 증거

- 비즈니스 롤백 시 Outbox와 이벤트가 모두 없음
- 커밋 직후 프로세스를 중단해도 재시작 후 이벤트 생성
- 같은 Outbox 재처리에서 `event_id` 기준 이벤트 한 건
- 변조한 이벤트의 무결성 검증 실패

### 2026-11-02 1차 구현 결과

- Flyway V5로 `audit_outbox`, `security_events`, 조회 인덱스와 `event_id` UNIQUE 추가
- 권한 요청 생성·승인·거절과 직접 역할 부여 성공을 같은 트랜잭션의 Outbox로 기록
- PostgreSQL `FOR UPDATE SKIP LOCKED` 기반 JDBC Publisher 구현
- Publisher 2개가 대기 이벤트 20건 처리: 선점 합계 20, 저장 20, 중복 0
- 역할 부여 실패 시 승인 상태와 승인 Outbox가 함께 롤백됨을 검증
- Flyway V6와 HMAC-SHA256 서명 추가, 정상 이벤트 `VALID`와 payload 변조 `INVALID` 검증
- Flyway V7과 Correlation ID 필터 추가, 응답·MDC·Outbox·보안 이벤트 연결
- HMAC v1 이전 이벤트 호환 및 correlation을 포함한 v2 서명 검증
- Flyway V8로 재시도 시각·실패 횟수·`FAILED` 상태와 Dead Letter 테이블 추가
- 이벤트 단위 독립 트랜잭션, 지수 backoff와 poison event 이후 정상 이벤트 계속 처리 구현
- Outbox backlog·최고 지연·발행 성공/실패·재시도·DLQ 메트릭과 Grafana 대시보드 추가
- 전체 54개 테스트 통과
- 상세 근거: [Transactional Outbox 감사 이벤트 구현 결과](transactional-outbox-implementation.md)
- 실패 격리 근거: [Outbox 실패 격리·재시도·Dead Letter 구현 결과](outbox-failure-isolation.md)

## Phase 3 — JDBC 이벤트 수집·검색

### 구현

- service credential 기반 배치 수집 API를 추가한다.
- `JdbcTemplate.batchUpdate`로 이벤트를 적재한다.
- 기간·유형·actor·IP 검색과 커서 페이지네이션을 구현한다.
- prepared statement, allowlist, 요청 크기, 민감정보 필터를 적용한다.

### 완료 증거

- 정상·중복·부분 오류 배치 계약 테스트
- SQL Injection 문자열이 데이터로만 처리됨
- 최대 조회 기간과 페이지 크기 제한 테스트
- Testcontainers PostgreSQL 실행 계획과 인덱스 사용 확인

## Phase 4 — 규칙 기반 탐지와 경보

### 구현

- 네 가지 MVP 탐지 규칙과 watermark를 구현한다.
- JDBC 시간 구간 집계 결과를 JPA 경보 Aggregate로 변환한다.
- fingerprint UNIQUE로 탐지 재실행을 멱등하게 만든다.
- 경보 조회, 담당자 할당, 상태 변경 API를 구현한다.

### 완료 증거

- 경계 시간, threshold 직전·직후 테스트
- 탐지 작업 재실행 시 경보 중복 없음
- 동시에 같은 경보를 처리할 때 낙관적 락 충돌 응답
- 이벤트에서 경보까지 지연 메트릭 확인

## Phase 5 — 운영 품질과 포트폴리오 패키징

### 구현

- Prometheus/Grafana에 Outbox, ingest, detection, alert 패널을 추가한다.
- GitHub Actions에서 빌드·테스트·정적 검사를 실행한다.
- Docker Compose로 앱, PostgreSQL, Prometheus, Grafana를 한 번에 실행한다.
- 100만 이벤트 seed 도구와 k6 조회·수집 시나리오를 만든다.
- README를 문제 → 데모 → 아키텍처 → 검증 수치 → 트레이드오프 순서로 개편한다.

### 완료 증거

- 새 환경에서 `docker compose up` 후 데모 가능
- 전체 CI 통과 배지
- 수집 처리량, 검색 p50/p95/p99, 탐지 지연 수치
- 장애 시나리오와 복구 결과가 포함된 사례 연구 3개

## 권장 커밋 단위

1. migration과 테스트 기반 정리
2. 권한 요청 상태 모델
3. 승인·거절 API와 동시성 테스트
4. Outbox 원자성
5. JDBC Publisher와 append-only 이벤트
6. 이벤트 수집·검색 보안
7. 탐지 규칙 한 개씩
8. 경보 워크플로
9. 부하 테스트와 관측 대시보드
10. 포트폴리오 README와 데모 자료

각 커밋은 실행 가능한 상태를 유지하고, 기능 커밋에는 최소 한 개의 실패 재현 또는 회귀 테스트를 포함한다.

