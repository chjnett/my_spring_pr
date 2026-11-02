# SecureFlow 백엔드 완성 가이드

## 이 문서의 목적

이 문서는 SecureFlow를 처음 받은 사람이 “무엇을 해결하는 서비스인지”, “어떤 순서로 실행·검증하는지”, “코드가 어떤 설계 결정을 담고 있는지”를 한 번에 이해하기 위한 운영·포트폴리오 가이드다. 프론트엔드 없이 API, PostgreSQL, Prometheus, Grafana만으로 전체 흐름을 검증한다.

## 제품 흐름

```text
권한 변경 ──JPA transaction──> audit_outbox
                                  │
                                  ▼
                      JDBC publisher / HMAC
                                  │
외부 서비스 ──batch ingest────> security_events ──JDBC aggregation──> security_alerts
                                                                    │
                                                                    ▼
                                                         담당자 지정 · 해결 처리
```

SecureFlow는 권한 요청·승인 같은 상태 중심 업무에는 JPA Aggregate를 사용하고, 대량 이벤트 적재·검색·시간 집계에는 JDBC를 사용한다. 이는 기술을 섞기 위한 목적이 아니라 트랜잭션 모델과 SQL 제어 범위가 다른 작업을 분리하기 위한 선택이다.

## 구현 범위

| 영역 | 완료된 기능 | 핵심 보장 |
| --- | --- | --- |
| Identity / RBAC | 사용자, BCrypt 인증, ADMIN·AUDITOR·MEMBER | API 경로별 인증·인가 |
| Access control | 권한 요청·승인·거절, 멱등성 키 | 동시 승인 1건만 성공 |
| Audit | Transactional Outbox, retry, DLQ, HMAC, correlation ID | 업무 변경과 감사 후보의 원자성 |
| Event ingest | service credential, JDBC batch, allowlist | 부분 성공·event ID 중복 방지 |
| Event search | 기간·유형·actor·IP·결과, cursor | 파라미터 바인딩·31일/200건 제한 |
| Detection | 네 규칙, watermark, fingerprint | 재실행 중복 없는 경보 생성 |
| Alert workflow | 할당·해결, `@Version` | 동시 수정은 409 충돌 |
| Operations | Docker, Prometheus/Grafana, CI, CodeQL, k6 | 재현 가능한 실행·검증 경로 |

## 처음부터 실행하기

필수 도구는 JDK 21, Docker Desktop, Docker Compose다. k6는 부하 테스트를 할 때만 설치한다.

### 1. 비밀값 준비

개발용에도 저장소 밖 환경 변수로 주입한다. macOS/Linux에서는 다음처럼 값을 만들 수 있다.

```bash
export AUDIT_HMAC_SECRET="$(openssl rand -base64 48)"
export SECURITY_EVENT_INGEST_API_KEY="$(openssl rand -base64 48)"
export SECUREFLOW_BOOTSTRAP_ADMIN_EMAIL='admin@example.com'
export SECUREFLOW_BOOTSTRAP_ADMIN_PASSWORD='change-this-password'
```

### 2. 전체 스택 기동

```bash
docker compose up --build --wait
```

| 서비스 | 주소 | 설명 |
| --- | --- | --- |
| SecureFlow API | `http://localhost:8081` | Spring Boot 백엔드 |
| Health | `http://localhost:8081/actuator/health` | 컨테이너 healthcheck |
| Prometheus | `http://localhost:9090` | 5초 scrape |
| Grafana | `http://localhost:3001` | `admin` / `secureflow` |
| PostgreSQL | `localhost:5433` | 개발용 DB |

Compose 안의 애플리케이션은 `DB_HOST=postgres`로 연결한다. IntelliJ에서 애플리케이션만 실행할 때는 기본값 `localhost:5433`을 그대로 사용한다.

### 3. 최소 데모

수집 API로 로그인 실패 이벤트 5건을 전송한다. `eventId`는 매번 새 UUID여야 한다.

```bash
curl -i -X POST http://localhost:8081/api/internal/security-events:batch \
  -H "X-SecureFlow-Ingest-Key: $SECURITY_EVENT_INGEST_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"events":[
    {"eventId":"11111111-1111-4111-8111-111111111111","eventType":"LOGIN_FAILED","actorId":42,"sourceIp":"203.0.113.10","outcome":"FAILURE","occurredAt":"2026-11-02T09:00:00Z","attributes":{"reasonCode":"BAD_PASSWORD"}},
    {"eventId":"22222222-2222-4222-8222-222222222222","eventType":"LOGIN_FAILED","actorId":42,"sourceIp":"203.0.113.10","outcome":"FAILURE","occurredAt":"2026-11-02T09:01:00Z","attributes":{"reasonCode":"BAD_PASSWORD"}}
  ]}'
```

탐지기는 기본 10분 tumbling window가 완성된 뒤 실행한다. 테스트에서 즉시 확인하려면 `DETECTION_WINDOW_SIZE`, `DETECTION_INGESTION_GRACE`를 짧게 설정하되, 운영값을 임의로 줄이면 late event 누락 가능성이 커진다.

### 4. 종료와 데이터 초기화

```bash
docker compose down
docker compose down -v  # PostgreSQL·Prometheus·Grafana의 개발 데이터도 제거
```

두 번째 명령은 named volume을 삭제하므로 필요한 데이터를 백업한 경우에만 사용한다.

## API 사용 순서

### 인증과 권한

`/api/users`로 사용자를 만든 뒤 bootstrap admin 환경 변수로 ADMIN을 생성하거나, 권한 요청 API로 역할을 부여한다. 보안 이벤트 조회와 경보 API에는 ADMIN 또는 AUDITOR Basic 인증이 필요하다.

### 보안 이벤트 수집

`POST /api/internal/security-events:batch`는 Basic 인증 대신 service credential header를 사용한다.

- Header: `X-SecureFlow-Ingest-Key`
- 최대 500개 이벤트
- 결과: 각 항목별 `ACCEPTED`, `DUPLICATE`, `REJECTED`
- 민감 키는 allowlist 밖이므로 거부

자세한 request/response와 입력 제약은 [이벤트 수집·검색 결과](security-event-ingestion-search.md)를 참고한다.

### 이벤트 검색

```bash
curl --user "$SECUREFLOW_BOOTSTRAP_ADMIN_EMAIL:$SECUREFLOW_BOOTSTRAP_ADMIN_PASSWORD" \
  'http://localhost:8081/api/security-events?eventType=LOGIN_FAILED&size=50'
```

커서는 응답의 `nextCursor`를 다음 요청의 `cursor`로 전달한다. offset 대신 `(occurred_at, id)` seek 조건을 사용하므로 새 이벤트가 유입돼도 페이지 경계가 안정적이다.

### 경보 처리

```bash
curl --user "$SECUREFLOW_BOOTSTRAP_ADMIN_EMAIL:$SECUREFLOW_BOOTSTRAP_ADMIN_PASSWORD" \
  'http://localhost:8081/api/alerts?status=OPEN'

curl -X POST --user "$SECUREFLOW_BOOTSTRAP_ADMIN_EMAIL:$SECUREFLOW_BOOTSTRAP_ADMIN_PASSWORD" \
  -H 'Content-Type: application/json' \
  -d '{"assigneeId":1}' \
  http://localhost:8081/api/alerts/1/assign

curl -X POST --user "$SECUREFLOW_BOOTSTRAP_ADMIN_EMAIL:$SECUREFLOW_BOOTSTRAP_ADMIN_PASSWORD" \
  -H 'Content-Type: application/json' \
  -d '{"resolutionNote":"사용자 확인 및 계정 보호 조치를 완료했습니다."}' \
  http://localhost:8081/api/alerts/1/resolve
```

경보 상태는 `OPEN → INVESTIGATING → RESOLVED`로만 전이한다. 동시에 변경된 경보는 `409 ALERT_CONCURRENTLY_MODIFIED`를 받고 최신 값을 다시 읽어야 한다.

## 탐지 규칙과 데이터 경계

| 규칙 | 조건 | 임계값 | 경보 심각도 |
| --- | --- | ---: | --- |
| actor 로그인 실패 | 동일 actor의 `LOGIN_FAILED/FAILURE` | 5 | HIGH |
| 다계정 IP 실패 | 동일 IP의 서로 다른 actor 로그인 실패 | 10 | CRITICAL |
| 접근 거부 급증 | 동일 actor의 `ACCESS_DENIED/DENIED` | 5 | MEDIUM |
| 특권 작업 실패 | 동일 actor의 `PRIVILEGED_ACTION/FAILURE·DENIED` | 3 | CRITICAL |

집계 구간은 `[from, to)`다. 종료 시각의 이벤트는 다음 구간에만 들어간다. watermark는 경보 생성이 완료된 뒤에만 이동하며, 중간 장애로 같은 window를 다시 처리해도 fingerprint UNIQUE가 중복 경보를 막는다.

## 관측과 운영 판단

Grafana에는 기존 Outbox 대시보드와 `SecureFlow · Security Pipeline` 대시보드가 자동 provisioning 된다.

| 지표 | 의미 | 경보 신호 |
| --- | --- | --- |
| `secureflow_security_events_ingest_total` | result별 수집 이벤트 | rejected 급증은 송신 계약 변화 |
| `secureflow_security_events_search_latency_seconds` | 검색 지연 histogram | p95 상승은 인덱스·부하 확인 |
| `secureflow_detection_alerts_created_total` | 신규 경보 | 실제 위험 또는 임계값 과민 |
| `secureflow_detection_alerts_duplicates_total` | 재실행 중복 제거 | watermark 재처리·장애 복구 관찰 |
| `secureflow_detection_event_to_alert_latency_seconds` | event→alert 지연 | polling/grace/DB 병목 점검 |
| `secureflow_alert_workflow_total` | 할당·해결·락 충돌 | conflict 증가는 운영자 동시 작업 신호 |

메트릭 label에는 사용자 ID, 이벤트 ID, IP처럼 값 종류가 무한히 늘어나는 식별자를 넣지 않았다. 개별 조사는 correlation ID, `event_id`, alert ID를 로그·DB에서 찾고, Prometheus에는 유한한 result/action label만 둔다.

## 테스트와 성능 검증

```bash
./gradlew test
```

통합 테스트는 Testcontainers PostgreSQL 16을 사용한다. 2026-11-02 기준 전체 71개 테스트가 통과하며, 이후에는 고정된 개수보다 CI의 실패 여부와 신규 기능의 회귀 테스트 포함 여부를 기준으로 확인한다.

대량 seed는 기본 100만 건이다. 실제 실행 전에는 먼저 작게 시작한다.

```bash
SECURITY_EVENT_INGEST_API_KEY="$SECURITY_EVENT_INGEST_API_KEY" \
SEED_COUNT=10000 \
node scripts/seed-security-events.mjs
```

수집·검색 k6 시나리오는 `loadtest/`에 있다.

```bash
k6 run -e SECURITY_EVENT_INGEST_API_KEY="$SECURITY_EVENT_INGEST_API_KEY" loadtest/security-event-ingest.js

AUTHORIZATION="Basic $(printf '%s' "$SECUREFLOW_BOOTSTRAP_ADMIN_EMAIL:$SECUREFLOW_BOOTSTRAP_ADMIN_PASSWORD" | base64)" \
k6 run -e AUTHORIZATION="$AUTHORIZATION" loadtest/security-event-search.js
```

seed/부하 테스트는 개발 DB에서만 수행한다. 운영 DB에 임의 이벤트를 넣거나 threshold를 충족시키는 것은 실제 경보·감사 데이터에 영향을 준다.

100만 건 실제 시드, k6 수집·검색 부하 테스트 실측 결과, 핵심 쿼리 EXPLAIN ANALYZE 실행 계획 및 Event-to-Alert 지연 통계는 [100만 건 벤치마크 및 성능 결과 보고서](performance-benchmark-2026-11-02.md)에 상세히 기록되어 있다.

## CI와 보안 분석

- `.github/workflows/verify.yml`: push/PR에서 JDK 21 전체 테스트와 Docker image build를 실행한다.
- `.github/workflows/codeql.yml`: Java 정적 분석을 push/PR 및 매주 월요일에 실행한다.
- 실패한 테스트 리포트는 GitHub Actions artifact로 업로드한다.

로컬 검증은 CI를 대체하지 않는다. 특히 Testcontainers, Docker multi-stage build, CodeQL은 GitHub Actions 결과까지 확인해야 한다.

## 완료 판정과 운영 전 과제

source별 credential registry, HMAC v3와 과거 키 key ring, application DB 계정의 append-only 권한, 100만 건 성능 측정까지 완료했다. 따라서 학습·포트폴리오 범위의 백엔드 기능은 동결한다.

실제 운영 배포를 결정하면 KMS/Secret Manager, TLS·네트워크 정책, 백업·복구 훈련, Alerting Rule, DLQ 재처리, sliding/incremental 탐지와 late-event 정책을 별도 작업으로 시작한다. 현재 10분 tumbling window의 실측 Event-to-Alert 평균은 약 8분이므로 낮은 지연 SLO가 필요하면 탐지 구조를 재설계해야 한다.

완료 범위, 중단할 작업과 운영 전 후속 과제는 [백엔드 완료 판정 문서](backend-status-2026-11-02.md)를 기준으로 한다.
