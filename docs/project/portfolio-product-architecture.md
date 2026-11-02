# SecureFlow 포트폴리오 제품·백엔드 아키텍처

## 1. 제품 정의

SecureFlow는 조직의 중요 권한 변경과 보안 이벤트를 수집하고, 위험한 행위를 규칙으로 판정하며, 조사 가능한 감사 근거를 제공하는 **접근 권한 감사·탐지 백엔드**다.

현재 구현된 사용자 CRUD, 권한 부여, 동시성 제어, 멱등성 처리를 버리지 않는다. 이 기능들을 다음 하나의 제품 흐름으로 연결한다.

> 관리자가 권한을 요청·승인하면 비즈니스 상태가 안전하게 변경되고, 변경 사실이 유실 없이 보안 이벤트로 남으며, 탐지 규칙이 위험도를 판정해 경보를 만든다.

### 포트폴리오에서 보여 줄 역량

- Spring Boot 모듈 경계와 트랜잭션 설계
- JPA와 JDBC를 작업 특성에 따라 선택하는 판단
- PostgreSQL 제약, 멱등성, 낙관적 락을 통한 데이터 정합성
- Transactional Outbox를 통한 비즈니스 변경과 감사 이벤트의 원자성
- Prepared Statement 기반 동적 검색과 대량 적재
- 규칙 기반 탐지, 감사 로그 보존, 민감정보 최소화
- Testcontainers 동시성·장애 복구 테스트와 관측 지표

### 이번 제품의 범위

포함한다.

- 사용자와 역할 관리
- 권한 요청, 승인·거절, 실제 권한 부여
- 내부 감사 이벤트와 외부 보안 이벤트 수집
- 시간 구간 기반 위험 탐지
- 경보 조회, 담당자 할당, 상태 변경
- 이벤트·경보 검색 및 운영 메트릭

초기 버전에서는 제외한다.

- 완전한 SIEM 대체
- 머신러닝 기반 이상 탐지
- Kafka, Elasticsearch, 다중 마이크로서비스
- 사용자 임의 SQL 실행 및 범용 로그 쿼리 언어
- 원본 비밀번호, 토큰, 요청 본문 전체 저장

## 2. 핵심 사용자 흐름

### 권한 변경 감사 흐름

1. 관리자가 사용자에게 필요한 역할을 요청한다.
2. 다른 승인자가 요청을 승인하거나 거절한다.
3. 승인 시 `RoleGrant`가 생성되고 요청 상태가 변경된다.
4. 같은 DB 트랜잭션에서 `audit_outbox` 레코드가 함께 생성된다.
5. Outbox Publisher가 레코드를 선점해 `security_events`에 멱등하게 적재한다.
6. Detection Engine이 이벤트를 규칙과 비교한다.
7. `ADMIN` 권한 부여처럼 위험한 행위면 경보를 생성한다.
8. 보안 담당자는 경보와 관련 이벤트를 조회하고 조사 상태를 갱신한다.

### 외부 보안 이벤트 흐름

1. 신뢰된 시스템이 로그인 실패, 접근 거부 등의 이벤트를 배치로 전송한다.
2. Ingest API가 인증, 크기 제한, 스키마 검증, 민감정보 필터링을 수행한다.
3. JDBC batch insert가 이벤트를 append-only 테이블에 기록한다.
4. 탐지 엔진이 `actor`, `sourceIp`, `eventType`, 시간 구간으로 집계한다.
5. 임계치를 넘으면 중복 방지 키를 사용해 경보 한 건을 만든다.

전체 목표 구조는 [대화형 아키텍처 다이어그램](../../.archify/architecture-secureflow-security-platform-20261102-150735/secureflow-security-platform.html)에서 확인한다.

## 3. 아키텍처 결정

### Modular Monolith 우선

배포 단위는 Spring Boot 애플리케이션 하나로 유지하되, 패키지와 저장소 소유권을 다음 모듈로 분리한다.

| 모듈 | 책임 | 주 저장 방식 |
| --- | --- | --- |
| `identity` | 사용자, 인증 주체, 조직 정보 | JPA |
| `accesscontrol` | 권한 요청·승인·부여, 멱등성 | JPA |
| `audit` | Outbox 생성, 이벤트 정규화·발행 | JPA + JDBC |
| `detection` | 규칙 관리, 시간 구간 집계, 위험 판정 | JPA + JDBC |
| `alert` | 경보 생성, 할당, 상태 전이 | JPA |
| `shared` | 오류 계약, 시간, 상관관계 ID | 프레임워크 독립 코드 |

모듈은 다른 모듈의 Entity나 Repository를 직접 사용하지 않는다. 애플리케이션 서비스가 Port 또는 ID 기반 DTO로 협력한다. 별도 서비스 분리는 처리량이나 조직 경계가 실제로 필요해질 때만 검토한다.

### JPA와 JDBC 사용 기준

#### JPA를 사용한다

- Entity 수명주기와 상태 전이가 중요한 데이터
- Aggregate 단위 트랜잭션과 변경 감지가 필요한 작업
- 일반 CRUD와 낙관적 락
- `users`, `role_grant_requests`, `role_grants`, `detection_rules`, `security_alerts`

#### JDBC를 사용한다

- 이벤트 대량 적재와 배치 insert
- `INSERT ... ON CONFLICT`, `FOR UPDATE SKIP LOCKED` 등 PostgreSQL 기능
- 시간 구간 집계, 조건 검색, 실행 계획과 인덱스를 직접 관리해야 하는 SQL
- `audit_outbox` 선점·발행과 `security_events` 읽기·쓰기

#### 금지 규칙

- 같은 테이블을 JPA Entity와 JDBC 도메인 모델이 동시에 수정하지 않는다.
- SQL 문자열에 사용자 입력을 이어 붙이지 않는다.
- 정렬 컬럼과 이벤트 타입은 서버 allowlist에서만 선택한다.
- Controller에서 `JdbcTemplate`이나 JPA Repository를 직접 호출하지 않는다.
- 보안 이벤트 저장 실패를 로그만 남기고 성공으로 처리하지 않는다.

### 데이터베이스와 스키마 관리

- PostgreSQL 하나를 사용하되 `business`, `security` 스키마로 소유권을 구분한다.
- 개발 단계의 `ddl-auto=update`를 제거하고 Flyway migration을 유일한 스키마 변경 수단으로 사용한다.
- 애플리케이션 시작 시 JPA는 `ddl-auto=validate`로 매핑만 검증한다.
- 운영 계정은 최소 권한을 가진다. `security.security_events`에는 `INSERT`와 필요한 `SELECT`만 부여하고 `UPDATE`, `DELETE`를 허용하지 않는다.
- 보안 이벤트 보존·삭제는 애플리케이션 API가 아니라 별도의 운영 migration 또는 보존 작업으로 수행한다.

## 4. 목표 데이터 모델

### 비즈니스 상태 — JPA 소유

#### `users`

현재 사용자 모델을 유지한다. `id`, `name`, `email`, 생성·수정 시각을 가진다.

#### `role_grant_requests`

- `id`
- `requester_id`, `target_user_id`
- `requested_role`
- `reason`
- `status`: `PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`
- `reviewer_id`, `reviewed_at`
- `version`: 동시 승인 방지를 위한 `@Version`
- `created_at`, `updated_at`

승인자는 요청자와 달라야 한다. 종료 상태에서 다시 승인·거절할 수 없다.

#### `role_grants`

현재 `(user_id, role)` UNIQUE 제약을 유지한다. 승인된 요청 ID를 선택적으로 참조해 변경 근거를 연결한다.

#### `detection_rules`

- `id`, `code`, `name`
- `event_type`
- `window_seconds`, `threshold`
- `severity`, `enabled`
- `version`, `created_at`, `updated_at`

초기에는 코드가 지원하는 규칙 유형만 설정 가능하다. 임의 SQL이나 표현식은 저장하지 않는다.

#### `security_alerts`

- `id`, `rule_id`
- `fingerprint` UNIQUE
- `severity`, `status`: `OPEN`, `INVESTIGATING`, `RESOLVED`, `FALSE_POSITIVE`
- `actor_id`, `source_ip`
- `first_event_at`, `last_event_at`, `event_count`
- `assignee_id`, `resolution_note`
- `version`, `created_at`, `updated_at`

`fingerprint`는 규칙, 대상, 탐지 시간 버킷을 조합해 재처리 시 경보 중복 생성을 막는다.

### 감사 파이프라인 — JPA/JDBC 경계

#### `audit_outbox`

비즈니스 트랜잭션이 JPA로 기록한다.

- `event_id` UUID UNIQUE
- `aggregate_type`, `aggregate_id`
- `event_type`, `occurred_at`
- `actor_id`, `correlation_id`
- `payload` JSONB
- `status`: `PENDING`, `PUBLISHED`, `FAILED`
- `attempt_count`, `next_attempt_at`, `published_at`, `last_error`

Publisher는 JDBC의 `FOR UPDATE SKIP LOCKED`로 작은 배치를 선점한다. 이벤트 저장과 발행 완료 표시는 하나의 로컬 트랜잭션으로 처리한다. 실패는 지수 backoff로 재시도하고, 최대 시도 횟수 초과 시 `FAILED`로 격리한다.

#### `security_events`

JDBC가 소유하는 append-only 이벤트 저장소다.

- `id` BIGINT identity
- `event_id` UUID UNIQUE
- `event_type`, `occurred_at`, `received_at`
- `actor_type`, `actor_id`
- `target_type`, `target_id`
- `source_ip` INET
- `result`, `risk_score`
- `correlation_id`, `source_system`
- `details` JSONB
- `integrity_hash`

인덱스는 실제 조회를 기준으로 시작한다.

- `(occurred_at DESC)`
- `(event_type, occurred_at DESC)`
- `(actor_id, occurred_at DESC)`
- `(source_ip, occurred_at DESC)`
- `event_id` UNIQUE

`integrity_hash`는 서버 비밀키로 이벤트의 정규화된 필드를 HMAC-SHA-256 처리한다. 이는 행 변경을 탐지하지만, 삭제까지 완전히 증명하는 외부 원장은 아니다. 문서와 API에서 “위변조 방지”가 아니라 “행 무결성 검증”으로 표현한다.

## 5. 공개 API 초안

### 권한 요청·승인

| Method | Path | 동작 |
| --- | --- | --- |
| `POST` | `/api/access-requests` | 권한 요청 생성 |
| `GET` | `/api/access-requests` | 상태·요청자·대상 사용자로 조회 |
| `POST` | `/api/access-requests/{id}/approve` | 승인 후 권한 부여 |
| `POST` | `/api/access-requests/{id}/reject` | 사유와 함께 거절 |

상태 변경 API는 `Idempotency-Key`를 요구한다. 동시 승인에서 한 요청만 성공하고 나머지는 `409 ACCESS_REQUEST_ALREADY_DECIDED`를 반환한다.

### 이벤트·경보

| Method | Path | 동작 |
| --- | --- | --- |
| `POST` | `/api/internal/security-events:batch` | 신뢰된 시스템의 이벤트 배치 수집 |
| `GET` | `/api/security-events` | 기간·유형·행위자·IP·결과로 검색 |
| `POST` | `/api/security-events/{eventId}/verify` | 저장된 이벤트의 HMAC 무결성 검증 |
| `GET` | `/api/security-alerts` | 심각도·상태·담당자로 경보 조회 |
| `PATCH` | `/api/security-alerts/{id}` | 담당자와 조사 상태 변경 |

검색 API의 기본 기간은 최근 24시간, 최대 기간은 31일, 페이지 크기는 기본 50·최대 200으로 제한한다. 커서 기반 페이지네이션은 `(occurred_at, id)`를 사용한다. 임의 정렬 필드는 받지 않는다.

### 공통 오류 계약

기존 `ErrorResponse` 형태를 유지하고 `correlationId`를 추가한다.

```json
{
  "timestamp": "2026-11-02T06:00:00Z",
  "status": 409,
  "code": "ACCESS_REQUEST_ALREADY_DECIDED",
  "message": "이미 처리된 권한 요청입니다.",
  "correlationId": "01J...",
  "fieldErrors": {}
}
```

## 6. 탐지 규칙 MVP

초기 규칙은 설명 가능하고 테스트 가능한 네 가지로 제한한다.

| 규칙 | 조건 | 심각도 | 중복 방지 기준 |
| --- | --- | --- | --- |
| `PRIVILEGED_ROLE_GRANTED` | `ADMIN` 또는 `AUDITOR` 권한 부여 | HIGH | 권한 요청 ID |
| `REPEATED_LOGIN_FAILURE` | 같은 actor 또는 IP에서 5분 내 실패 5회 | MEDIUM | actor/IP + 5분 버킷 |
| `REPEATED_ACCESS_DENIED` | 같은 actor가 10분 내 접근 거부 3회 | MEDIUM | actor + 10분 버킷 |
| `IDEMPOTENCY_KEY_MISUSE` | 같은 키를 다른 요청 본문으로 재사용 | HIGH | actor + key hash + 1시간 버킷 |

탐지기는 마지막 성공 지점을 watermark로 저장하고 안전 중복 구간을 포함해 다시 읽는다. `security_alerts.fingerprint` UNIQUE 제약으로 재처리를 멱등하게 만든다.

## 7. 보안 설계

### 인증과 인가

- 사용자 API는 인증된 사용자만 접근한다.
- 권한 요청은 `MEMBER`, 승인은 `ADMIN`, 경보 조회·처리는 `AUDITOR` 또는 `ADMIN`만 허용한다.
- 외부 이벤트 수집 API는 일반 사용자 JWT가 아니라 별도의 service credential과 source allowlist를 사용한다.
- actor ID는 요청 본문에서 신뢰하지 않고 인증 정보에서 결정한다.

### 로그 안전성

- 비밀번호, Authorization 헤더, 세션·JWT, 주민번호 같은 비밀정보는 저장하지 않는다.
- 이메일은 탐지에 꼭 필요한 경우에만 정규화·마스킹해 details에 저장한다.
- 자유 형식 payload는 허용된 키만 통과시키고 크기를 제한한다.
- JDBC는 named parameter와 prepared statement만 사용한다.
- SQL Injection 테스트는 값, 필터, 정렬 allowlist를 각각 검증한다.
- 이벤트 HMAC 키와 DB 비밀번호는 환경변수·Secret으로 주입하고 저장소에 커밋하지 않는다.

### 추적성

- 모든 요청에 `X-Correlation-Id`를 발급하거나 검증해 MDC에 저장한다.
- HTTP 응답, 애플리케이션 구조화 로그, Outbox, Security Event에 같은 ID를 전파한다.
- 운영 로그는 처리 과정 관찰용이고, `security_events`가 감사 판단의 기준 데이터다.

## 8. 일관성·장애 처리

- 권한 승인과 Outbox 생성은 같은 JPA 트랜잭션이다. 어느 하나라도 실패하면 모두 롤백한다.
- Outbox Publisher는 최소 한 번 처리하며 `security_events.event_id` UNIQUE로 중복을 제거한다.
- 탐지기는 이벤트를 재처리할 수 있고 경보 fingerprint로 중복을 제거한다.
- 이벤트 저장소 장애 시 비즈니스 트랜잭션은 Outbox까지만 성공할 수 있다. Outbox lag 경보가 장애를 드러낸다.
- 감사 이벤트 payload 직렬화 실패는 비즈니스 커밋 전에 실패시켜 감사 근거 없는 권한 변경을 허용하지 않는다.
- 경보 생성 실패는 이벤트를 잃지 않으며 다음 탐지 주기에 재시도한다.

## 9. 관측성과 운영 지표

Prometheus에 다음 지표를 노출하고 Grafana에서 한 화면으로 확인한다.

- `secureflow_outbox_pending_total`
- `secureflow_outbox_oldest_age_seconds`
- `secureflow_security_events_ingested_total{type,source}`
- `secureflow_detection_runs_total{result}`
- `secureflow_detection_lag_seconds`
- `secureflow_alerts_created_total{rule,severity}`
- `secureflow_jdbc_query_seconds{operation}`
- HTTP 요청 수·오류율·p95 latency

경보 기준은 Outbox oldest age 60초 초과, 탐지 지연 120초 초과, Publisher 연속 실패로 시작한다.

## 10. 완료 기준

이 프로젝트가 포트폴리오 제품으로 보이려면 다음을 모두 증명해야 한다.

- 권한 요청부터 경보 조사까지 한 번에 재현되는 데모 시나리오
- 일반 CRUD는 JPA, 이벤트 대량 적재·검색·집계는 JDBC라는 명확한 근거
- 동일 권한 승인 동시 요청에서 권한과 감사 이벤트가 각각 한 건만 생성됨
- Outbox 처리 중 강제 실패 후 재시작해도 이벤트가 유실·중복되지 않음
- SQL Injection payload가 검색 조건을 탈출하지 못함
- 이벤트 100만 건 기준 핵심 검색 쿼리의 실행 계획과 p95 수치 기록
- 테스트, Docker Compose, CI가 새 환경에서 한 명령으로 재현됨
- README에 문제, 아키텍처 선택, 수치, 트레이드오프가 먼저 보임

