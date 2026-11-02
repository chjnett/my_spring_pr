# SecureFlow 백엔드 포트폴리오 가이드

## 한 문장 소개

SecureFlow는 권한 변경을 감사 가능한 이벤트로 남기고, 외부 보안 로그를 JDBC 배치로 수집·검색하며, 규칙 기반 탐지와 경보 대응까지 연결한 Spring Boot 보안 운영 플랫폼이다.

단순 CRUD가 아니라 **트랜잭션 원자성, 멱등성, 동시성 제어, 감사 무결성, DB 권한, 관측 가능성**을 설계·검증한 것이 핵심이다.

## 문제와 설계 결정

| 문제 | 설계 | 근거 |
| --- | --- | --- |
| 권한 승인 중 중복 부여 | `@Version` 낙관적 락과 상태 전이 | 두 승인자 동시 요청에서 1건만 성공 |
| API 재시도로 중복 생성 | idempotency key와 저장된 응답 replay | 동일 키 20건 요청에서 생성 1건 |
| 업무 변경과 감사 기록 불일치 | Transactional Outbox + JDBC publisher | 업무 롤백 시 outbox도 함께 롤백 |
| 대량 보안 로그 수집 | service credential + `JdbcTemplate.batchUpdate` | 500건 batch, 항목별 부분 성공 |
| 무단 이벤트 변경 | HMAC v1/v2/v3와 append-only DB 권한 | v3은 검색 컬럼 변조도 검출 |
| 탐지 작업 재실행 | window fingerprint UNIQUE | 재실행 시 경보 중복 없음 |
| 운영 가시성 부족 | Micrometer, Prometheus, Grafana, k6 | 수집·검색·탐지·경보 지표 제공 |

## 아키텍처

```text
Client/Admin ── Basic Auth ──> Access Request / Alert APIs ──> JPA aggregates
                                        │                             │
                                        └──── transaction ───────> audit_outbox
                                                                        │
External source ─ credential registry ─> JDBC batch ingest ─> security_events <── outbox publisher
                                                    │              │
                                                    │              └─ HMAC signature / append-only grant
                                                    └── source, IP, outcome indexes
                                                                   │
Detection scheduler ─ JDBC aggregation / watermark ───────────────┘
                                                                   │
                                                     SecurityAlert JPA aggregate
                                                                   │
                                              Prometheus ──> Grafana dashboards
```

## 구현 범위

### Phase 0~1: Identity와 권한 승인

- 사용자 credential은 BCrypt로 저장하고 Spring Security Basic Auth/RBAC로 보호한다.
- 권한 요청은 `PENDING → APPROVED|REJECTED` 상태 전이를 사용한다.
- 요청자와 승인자는 분리하고, 종료 상태 재처리와 자기 승인을 차단한다.

### Phase 2: 감사 이벤트

- 권한 변경과 outbox 기록을 하나의 DB transaction으로 묶는다.
- `FOR UPDATE SKIP LOCKED` publisher가 여러 인스턴스에서도 중복 없이 이벤트를 전달한다.
- 실패 이벤트는 backoff, retry, DLQ로 격리한다.

### Phase 3~4: 로그 분석과 탐지

- 외부 수집 API는 허용된 event type·attribute·크기만 받고, injection 문자열도 데이터로 처리한다.
- 검색은 기간, type, actor, IP, outcome과 seek cursor를 지원한다.
- 로그인 실패, 다계정 IP 실패, 접근 거부, 특권 작업 실패 네 규칙이 고정 window와 watermark로 경보를 만든다.
- 경보는 `OPEN → INVESTIGATING → RESOLVED` 상태와 optimistic locking을 사용한다.

### Phase 5~6: 운영과 신뢰 경계

- Docker Compose로 PostgreSQL, 앱, Prometheus, Grafana를 실행한다.
- GitHub Actions는 테스트·Docker image build·CodeQL을 실행한다.
- service credential은 BCrypt hash, source, 만료, revoke, 마지막 사용 시각을 registry에 보관한다.
- HMAC v3은 `correlationId`, `source`, `sourceIp`, `outcome`까지 canonical data에 넣는다. v1/v2 이벤트는 계속 검증한다.
- Compose에서는 Flyway migrator와 application DB 계정을 분리하고, application 계정은 `security_events`를 SELECT/INSERT만 할 수 있다.

## 실행 방법

```bash
export AUDIT_HMAC_SECRET="$(openssl rand -base64 48)"
export SECURITY_EVENT_INGEST_API_KEY="$(openssl rand -base64 48)"
export SECUREFLOW_BOOTSTRAP_ADMIN_EMAIL="admin@example.com"
export SECUREFLOW_BOOTSTRAP_ADMIN_PASSWORD="change-this-password"
docker compose up --build
```

- API: `http://localhost:8081`
- health: `http://localhost:8081/actuator/health`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3001` (`admin` / `secureflow`, 개발 전용)

기존 named volume은 이전 단일 DB 계정으로 초기화되어 있을 수 있다. 권한 분리 Compose를 처음 적용할 때는 개발 데이터가 불필요한 경우에만 `docker compose down -v` 후 다시 실행한다.

## 키 회전 절차

1. 새 secret을 KMS/Secret Manager에 보관하고 새 `AUDIT_HMAC_KEY_ID`, `AUDIT_HMAC_SECRET`으로 배포한다.
2. 이전 키를 배포 설정의 `secureflow.audit.integrity.verification-keys` map에 유지한다. 예: `local-v1: ${OLD_AUDIT_HMAC_SECRET}`.
3. 신규 이벤트가 새 `keyId`와 v3으로 생성되는지 확인한다.
4. 이전 키로 서명된 모든 보존 기간 이벤트가 만료된 뒤에만 verification key를 제거한다.

키 원문은 DB나 Git에 저장하지 않는다. registry secret도 BCrypt hash만 DB에 저장한다.

## 검증과 성능

- Testcontainers PostgreSQL 통합 테스트: 승인 동시성, outbox, HMAC 변조, 수집 부분 성공, cursor, 탐지, 경보 전이를 검증한다.
- HMAC v3 검색 컬럼 변조, 과거 키 검증, application role의 `UPDATE`/`DELETE` 권한 부재도 자동 테스트한다.
- `scripts/seed-security-events.mjs`: 최대 100만 건 seed.
- `loadtest/security-event-ingest.js`, `loadtest/security-event-search.js`: k6 시나리오.
- 실제 환경에서는 p50/p95/p99, 수집 처리량, event-to-alert latency를 실행 날짜·데이터 양과 함께 기록한다.

## 남은 운영 과제

1. Secret Manager/KMS와 TLS·네트워크 정책을 실제 배포 환경에 적용한다.
2. sliding window와 late-event 재처리 정책을 추가한다.
3. 탐지 규칙/threshold를 관리 가능한 정책 데이터로 분리한다.
4. 부하 측정값과 장애 복구 사례를 README에 수치로 고정한다.

상세 명령·API 계약은 [백엔드 전체 구현·운영 가이드](backend-completion-guide.md)를, 단계별 설계 근거는 [구현 로드맵](implementation-roadmap.md)을 참고한다.
