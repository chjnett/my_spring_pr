# 규칙 기반 탐지와 경보 워크플로 구현 결과

## 결론

SecureFlow Phase 4에서는 `security_events`를 JDBC로 시간 구간 집계하고, 탐지 결과를 JPA `SecurityAlert` Aggregate로 관리하도록 구현했다. 탐지 작업은 고정 10분 구간과 30초 ingestion grace를 사용하며, 성공한 구간까지만 watermark를 전진시킨다.

같은 구간을 장애 후 다시 실행해도 `rule + subject + window`의 SHA-256 fingerprint와 UNIQUE 제약으로 경보가 한 건만 생성된다. 경보는 `OPEN → INVESTIGATING → RESOLVED` 상태를 가지며 `@Version` 낙관적 락으로 동시 처리를 방어한다.

## MVP 탐지 규칙

| 규칙 | 조건 | 임계값 | 심각도 | subject |
| --- | --- | ---: | --- | --- |
| `ACTOR_LOGIN_FAILURE_BURST` | `LOGIN_FAILED + FAILURE` | 동일 actor 5회 | HIGH | actor ID |
| `IP_MULTI_ACCOUNT_LOGIN_FAILURE` | `LOGIN_FAILED + FAILURE` | 동일 IP에서 서로 다른 actor 10명 | CRITICAL | IP |
| `ACTOR_ACCESS_DENIED_BURST` | `ACCESS_DENIED + DENIED` | 동일 actor 5회 | MEDIUM | actor ID |
| `ACTOR_PRIVILEGED_ACTION_FAILURE` | `PRIVILEGED_ACTION + FAILURE/DENIED` | 동일 actor 3회 | CRITICAL | actor ID |

SQL의 기간 조건은 `occurred_at >= from AND occurred_at < to`다. 종료 시각을 제외하면 인접 구간이 같은 이벤트를 동시에 포함하지 않는다.

## 처리 흐름

```text
DetectionPollingJob (기본 비활성, 30초 polling)
        │
        ▼
DetectionWatermarkService
  ├─ MVP_RULES watermark 행 잠금
  ├─ 완성된 10분 구간이 없으면 종료
  └─ 처리할 [from, to) 확정
        │
        ▼
DetectionQueryRepository (JDBC)
  └─ 네 규칙 GROUP BY / HAVING 집계
        │
        ▼
AlertCreationService (JPA, REQUIRES_NEW)
  ├─ DetectionCandidate → SecurityAlert
  ├─ fingerprint UNIQUE로 재실행 중복 방지
  └─ 경보별 독립 commit
        │
        ▼
모든 규칙 완료 후 watermark → to
```

경보 생성이 커밋된 뒤 watermark 갱신 전에 프로세스가 종료되면 같은 구간을 다시 읽는다. 이미 생성된 경보는 fingerprint 충돌로 중복 처리되고, 나머지 후보만 생성된다. 반대로 탐지 SQL이나 watermark 트랜잭션이 실패하면 watermark는 전진하지 않는다.

## 경보 API

ADMIN 또는 AUDITOR만 접근할 수 있다.

```http
GET  /api/alerts?status=OPEN
GET  /api/alerts/{alertId}
POST /api/alerts/{alertId}/assign
POST /api/alerts/{alertId}/resolve
```

담당자 할당:

```json
{"assigneeId": 42}
```

담당자를 할당하면 상태가 `INVESTIGATING`으로 바뀐다. 조사 중인 경보만 해결할 수 있다.

```json
{"resolutionNote": "사용자 확인 후 계정 보호 완료"}
```

두 분석가가 같은 version을 동시에 수정하면 한 트랜잭션만 성공하고 다른 요청은 `409 ALERT_CONCURRENTLY_MODIFIED`로 변환된다.

## 실행 설정

탐지 스케줄러는 기본적으로 꺼져 있다.

```bash
DETECTION_ENABLED=true \
DETECTION_FIXED_DELAY=30000 \
DETECTION_WINDOW_SIZE=10m \
DETECTION_INGESTION_GRACE=30s \
AUDIT_HMAC_SECRET='replace-with-at-least-32-characters' \
./gradlew bootRun
```

polling은 처리할 구간이 생겼는지 확인하는 주기이고, window size는 집계 범위다. 30초마다 실행하더라도 watermark는 완성된 10분 구간 단위로만 전진한다. backlog가 있으면 한 polling에 한 구간씩 처리해 장시간 트랜잭션을 피한다.

## 관측 지표

- `secureflow_detection_alerts_created_total`: 생성된 경보
- `secureflow_detection_alerts_duplicates_total`: fingerprint 중복 제거
- `secureflow_detection_event_to_alert_latency_seconds`: 마지막 원인 이벤트부터 경보 생성까지의 지연

지연 Timer는 음수 시 0으로 보정한다. Phase 5에서는 Grafana 패널과 지연 SLO, backlog watermark lag를 추가한다.

## 검증 결과

| 시나리오 | 결과 |
| --- | --- |
| 로그인 실패 4회 | 경보 0건 |
| 종료 경계 직전 5번째 이벤트 | 경보 1건 |
| 정확히 `to`에 발생한 이벤트 | 다음 구간 대상으로 현재 경보에서 제외 |
| 같은 구간 재실행 | 후보 1, 신규 0, duplicate 1 |
| 네 규칙 임계값 충족 | 서로 다른 규칙 경보 4건 |
| 10분 미완성 구간 polling | watermark 전진 없음 |
| OPEN 경보 즉시 resolve | `409 INVALID_ALERT_TRANSITION` |
| 담당자 할당 후 resolve | `INVESTIGATING → RESOLVED` |
| 동시 담당자 변경 | 성공 1, 낙관적 락 실패 1, version 1 |
| 경보 API 미인증 | `401` |
| 이벤트→경보 Timer | 생성 건수 이상 기록 확인 |
| 전체 회귀 | 66 tests, 0 failures, 0 errors, 0 skipped |

검증 클래스는 `RuleDetectionPostgresTest`이며 PostgreSQL 16 Testcontainers를 사용한다.

## 현재 한계와 다음 개선

현재 규칙은 겹치지 않는 tumbling window다. 경계 앞 4건과 다음 구간 초반 1건처럼 실제 10분 안에는 5건이지만 서로 다른 구간에 나뉜 이벤트는 탐지하지 못할 수 있다. 운영 탐지 정확도를 높이려면 다음 중 하나를 선택해야 한다.

1. sliding window와 별도의 suppression 기간
2. rule별 최근 상태를 저장하는 incremental counter
3. 스트림 처리 엔진의 event-time window와 allowed lateness

현재 30초 grace보다 늦게 도착한 이벤트도 이미 확정된 구간에는 반영되지 않는다. Phase 5 부하·장애 실험에서 실제 지연 분포를 측정한 뒤 grace와 재처리 정책을 정해야 한다. 또한 임계값은 코드에 고정되어 있으므로 운영 변경 이력과 승인까지 필요해지면 versioned rule configuration으로 분리한다.
