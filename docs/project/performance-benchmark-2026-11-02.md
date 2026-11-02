# 개발 DB 100만 건 벤치마크 및 성능 측정 결과 보고서

## 1. 벤치마크 개요

본 문서는 SecureFlow 백엔드를 대상으로 개발 DB 환경에서 **100만 건(1,000,000건)**의 대규모 보안 이벤트를 시딩하고, **k6 부하 테스트(이벤트 수집 및 검색)**, **PostgreSQL 핵심 쿼리 실행 계획(`EXPLAIN ANALYZE, BUFFERS`)**, 그리고 **Event-to-Alert 탐지 지연 시간**을 측정한 실측 결과 보고서다.

모든 테스트는 기존 데이터와 설정을 보존하기 위해 독립된 Docker Compose 벤치마크 프로젝트(`secureflow-benchmark`)와 격리된 볼륨 환경에서 수행되었다.

---

## 2. 측정 일시 및 환경 정보

| 항목 | 상세 사양 |
| --- | --- |
| **측정 일시** | 2026-11-02 |
| **호스트 OS** | macOS 26.6.2 (Darwin 25G83) |
| **하드웨어** | Apple M4, 16GB Unified Memory |
| **컨테이너 런타임** | Docker Engine 29.2.1 / Docker Compose v2.x |
| **애플리케이션 프레임워크** | Spring Boot 4.1.2 (Java 21, Eclipse Temurin JRE/JDK Alpine) |
| **데이터베이스** | PostgreSQL 16-alpine (Docker 컨테이너) |
| **부하 테스트 도구** | k6 (grafana/k6:latest) |
| **데이터 시딩 스크립트** | Node.js v26.5.0 (`scripts/seed-security-events.mjs`) |

---

## 3. 100만 건 데이터 시드 (Data Seeding) 결과

Node.js 배치 시딩 스크립트를 사용하여 단일 배치당 500건씩, 총 2,000회의 HTTP POST(`/api/internal/security-events:batch`) 호출을 통해 적재를 완료했다.

### 3.1 시딩 성능 지표

| 지표 | 측정 결과 |
| --- | --- |
| **총 적재 건수** | 1,000,000 events |
| **배치 크기 (Batch Size)** | 500 events / request |
| **총 소요 시간** | 320.7초 (약 5분 20초) |
| **초당 처리량 (Throughput)** | **3,118.2 events / sec** |
| **오류 발생률** | 0.00% (0 / 2,000 배치 실패) |

### 3.2 적재 후 PostgreSQL 스토리지 용량

```sql
SELECT pg_size_pretty(pg_total_relation_size('security_events')) AS total_size,
       pg_size_pretty(pg_relation_size('security_events')) AS table_size,
       pg_size_pretty(pg_indexes_size('security_events')) AS index_size;
```

| 구분 | 용량 | 비고 |
| --- | --- | --- |
| **테이블 힙 (Table Data)** | 411 MB | 100만 건 JSON 페이로드 및 메타데이터 |
| **인덱스 (Indexes)** | 340 MB | PK·UNIQUE와 검색용 B-tree 인덱스 전체 |
| **총 릴레이션 크기** | **751 MB** | 100만 건 기준 전체 사용 디스크 용량 |

---

## 4. k6 부하 테스트 결과

### 4.1 이벤트 수집 부하 테스트 (`loadtest/security-event-ingest.js`)

- **시나리오**: `ramping-arrival-rate` (10 RPS 시작 → 30초간 50 RPS로 ramp-up → 60초간 50 RPS 유지 → 15초간 0 RPS ramp-down, 총 105초)
- **임계값 (Thresholds)**:
  - `http_req_failed`: `rate < 0.01` (1% 미만)
  - `http_req_duration`: `p(95) < 500ms`

#### 측정 결과

| 지표 | 실측치 | 목표(Threshold) | 판정 |
| --- | --- | --- | :---: |
| **총 요청 수 (http_reqs)** | 4,233 requests (평균 40.23 RPS) | - | - |
| **요청 성공률 (Checks)** | **100.00%** (4,233 / 4,233) | 100% | **PASS** |
| **실패율 (http_req_failed)** | **0.00%** (0건) | `< 0.01` (1%) | **PASS** |
| **평균 응답 시간 (avg)** | 154.37 ms | - | - |
| **중앙값 (p50 / med)** | 113.21 ms | - | - |
| **90 백분위수 (p90)** | 143.26 ms | - | - |
| **95 백분위수 (p95)** | **206.58 ms** | `< 500 ms` | **PASS** |
| **최대 지연 (max)** | 3.11 s | - | - |

> **분석**: 100만 건 데이터가 적재되어 있는 상태에서도 50 RPS 수집 부하 하에서 p95 응답 속도가 206ms로 안정적이었으며, 데이터 무결성 검증 및 인제스트 파이프라인에서 단 한 건의 유실이나 오류도 발생하지 않았다.

---

### 4.2 이벤트 검색 부하 테스트 (`loadtest/security-event-search.js`)

- **시나리오**: `constant-arrival-rate` (30 RPS 고정, 90초 지속)
- **대상 엔드포인트**: `GET /api/security-events?eventType=LOGIN_FAILED&size=50` (24시간 기간 범위)
- **임계값 (Thresholds)**:
  - `http_req_failed`: `rate < 0.01` (1% 미만)
  - `http_req_duration`: `p(95) < 300ms`, `p(99) < 700ms`

#### 측정 결과

| 지표 | 실측치 | 목표(Threshold) | 판정 |
| --- | --- | --- | :---: |
| **총 요청 수 (http_reqs)** | 2,701 requests (29.94 RPS) | - | - |
| **요청 성공률 (Checks)** | **100.00%** (2,701 / 2,701) | 100% | **PASS** |
| **실패율 (http_req_failed)** | **0.00%** (0건) | `< 0.01` (1%) | **PASS** |
| **평균 응답 시간 (avg)** | 119.09 ms | - | - |
| **중앙값 (p50 / med)** | 115.04 ms | - | - |
| **90 백분위수 (p90)** | 124.81 ms | - | - |
| **95 백분위수 (p95)** | **140.74 ms** | `< 300 ms` | **PASS** |
| **99 백분위수 (p99)** | **209.03 ms** | `< 700 ms` | **PASS** |
| **전송 데이터 (Network In)** | 58 MB (641 kB/s) | - | - |

> **분석**: 100만 건 테이블을 대상으로 24시간 범위 필터 및 커서 정렬을 수행했을 때 p95 140ms, p99 209ms를 기록했다. 실행 계획에서는 `idx_security_events_type_occurred_at`와 Incremental Sort가 선택됐다. API 수치는 인증, JDBC 매핑, JSON 직렬화와 컨테이너 네트워크 시간을 포함하므로 아래의 DB 단독 실행 시간과 직접 비교하면 안 된다.

---

## 5. 핵심 쿼리 실행 계획 (`EXPLAIN ANALYZE, BUFFERS`)

100만 건 적재 상태에서 실제 백엔드 핵심 쿼리 3종의 실행 계획을 PostgreSQL 내부에서 직접 분석했다.

### 5.1 커서 기반 이벤트 검색 쿼리

```sql
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT id, event_id, event_type, actor_id, source, source_ip, outcome,
       occurred_at, ingested_at, correlation_id, payload
FROM security_events
WHERE occurred_at >= '2026-11-01 11:00:00+00'
  AND occurred_at <= '2026-11-02 11:35:00+00'
  AND event_type = 'LOGIN_FAILED'
ORDER BY occurred_at DESC, id DESC
LIMIT 51;
```

#### 실행 계획 결과

```text
Limit  (cost=0.63..12.89 rows=51 width=246) (actual time=0.311..0.359 rows=51 loops=1)
  Output: id, event_id, event_type, actor_id, source, source_ip, outcome, occurred_at, ingested_at, correlation_id, payload
  Buffers: shared hit=63
  ->  Incremental Sort  (cost=0.63..226798.22 rows=943587 width=246) (actual time=0.309..0.353 rows=51 loops=1)
        Output: id, event_id, event_type, actor_id, source, source_ip, outcome, occurred_at, ingested_at, correlation_id, payload
        Sort Key: security_events.occurred_at DESC, security_events.id DESC
        Presorted Key: security_events.occurred_at
        Full-sort Groups: 2  Sort Method: quicksort  Average Memory: 41kB  Peak Memory: 41kB
        Buffers: shared hit=63
        ->  Index Scan using idx_security_events_type_occurred_at on public.security_events  (cost=0.42..184747.21 rows=943587 width=246) (actual time=0.081..0.241 rows=52 loops=1)
              Index Cond: (((security_events.event_type)::text = 'LOGIN_FAILED'::text) AND (security_events.occurred_at >= '2026-11-01 11:00:00+00'::timestamp with time zone) AND (security_events.occurred_at <= '2026-11-02 11:35:00+00'::timestamp with time zone))
              Buffers: shared hit=54
Planning Time: 2.366 ms
Execution Time: 0.448 ms
```

- **실행 시간**: **0.448 ms**
- **인덱스**: `idx_security_events_type_occurred_at`
- **버퍼 효율**: `shared hit=63`, 디스크 Read 0 (모두 버퍼 풀 적중)
- **분석**: 전체 94만 건의 `LOGIN_FAILED` 이벤트 중 상위 51건을 읽기 위해 인덱스 스캔 후 Incremental Sort를 수행하여 0.45ms 만에 쿼리가 완료됨. 커서(`occurred_at < cursorTime`)를 추가 적용한 페이지 탐색에서도 실행 시간은 **9.426 ms**로 유지됨.

---

### 5.2 Actor 로그인 실패 버스트 탐지 쿼리 (10분 윈도우)

```sql
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT cast(actor_id as varchar) subject_value, count(*) event_count,
       min(occurred_at) first_event_at, max(occurred_at) last_event_at
FROM security_events
WHERE occurred_at >= '2026-11-02 11:20:00+00' AND occurred_at < '2026-11-02 11:30:00+00'
  AND event_type = 'LOGIN_FAILED' AND outcome = 'FAILURE'
GROUP BY actor_id
HAVING count(*) >= 5;
```

#### 실행 계획 결과

```text
HashAggregate  (cost=6787.36..6822.34 rows=823 width=64) (actual time=2.212..2.213 rows=0 loops=1)
  Output: (actor_id)::character varying, count(*), min(occurred_at), max(occurred_at), actor_id
  Group Key: security_events.actor_id
  Filter: (count(*) >= 5)
  Batches: 1  Memory Usage: 369kB
  Buffers: shared hit=1191 read=12
  ->  Index Scan using idx_security_events_outcome_occurred on public.security_events  (cost=0.42..6759.03 rows=2833 width=16) (actual time=0.113..1.714 rows=1222 loops=1)
        Index Cond: (((security_events.outcome)::text = 'FAILURE'::text) AND (security_events.occurred_at >= '2026-11-02 11:20:00+00'::timestamp with time zone) AND (security_events.occurred_at < '2026-11-02 11:30:00+00'::timestamp with time zone))
        Filter: ((security_events.event_type)::text = 'LOGIN_FAILED'::text)
Planning Time: 1.236 ms
Execution Time: 2.374 ms
```

- **실행 시간**: **2.374 ms**
- **인덱스**: `idx_security_events_outcome_occurred`
- **분석**: 10분 구간에 해당하는 약 1,222건의 레코드를 인덱스로 필터링하고 인메모리 HashAggregate(369kB 메모리)를 통해 2.37ms 만에 집계 완료.

---

### 5.3 IP 기준 다계정 로그인 실패 탐지 쿼리 (10분 윈도우)

```sql
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT source_ip subject_value, count(*) event_count,
       min(occurred_at) first_event_at, max(occurred_at) last_event_at
FROM security_events
WHERE occurred_at >= '2026-11-02 11:20:00+00' AND occurred_at < '2026-11-02 11:30:00+00'
  AND event_type = 'LOGIN_FAILED' AND outcome = 'FAILURE'
  AND source_ip IS NOT NULL
GROUP BY source_ip
HAVING count(distinct actor_id) >= 10;
```

#### 실행 계획 결과

```text
GroupAggregate  (cost=6921.48..6994.83 rows=823 width=37) (actual time=2.192..2.415 rows=61 loops=1)
  Output: source_ip, count(*), min(occurred_at), max(occurred_at)
  Group Key: security_events.source_ip
  Filter: (count(DISTINCT security_events.actor_id) >= 10)
  Buffers: shared hit=1209
  ->  Sort  (cost=6921.48..6928.56 rows=2833 width=29) (actual time=2.159..2.211 rows=1222 loops=1)
        Sort Key: security_events.source_ip, security_events.actor_id
        Sort Method: quicksort  Memory: 115kB
        ->  Index Scan using idx_security_events_outcome_occurred on public.security_events  (cost=0.42..6759.03 rows=2833 width=29) (actual time=0.036..1.067 rows=1222 loops=1)
Planning Time: 0.897 ms
Execution Time: 2.565 ms
```

- **실행 시간**: **2.565 ms**
- **인덱스**: `idx_security_events_outcome_occurred`
- **집계 결과**: 임계값(동일 IP 기준 10개 이상 서로 다른 actor)을 충족하는 61개 IP 탐지 완료.
- **분석**: `count(distinct actor_id)`와 같은 카디널리티 연산이 포함되었음에도 인덱스 스캔과 퀵소트(115kB) 후 GroupAggregate를 수행하여 2.56ms 만에 61건의 이상 징후를 추출함.

---

## 6. Event-to-Alert 지연 시간 측정

### 6.1 탐지 윈도우 메커니즘
- **Window 크기 (`windowSize`)**: 10분 (`10m`)
- **수집 유예 시간 (`ingestionGrace`)**: 30초 (`30s`)
- **스케줄러 주기 (`fixedDelay`)**: 30초 (`30000ms`)
- **원리**: 텀블링 윈도우(`[from, to)`)가 완전히 종료되고 지연 수집 유예 기간(30초)이 경과한 뒤 `DetectionPollingJob`이 DB에서 후보를 집계하여 `security_alerts`에 적재한다.

### 6.2 Prometheus 메트릭 실측치

Spring Boot Actuator Prometheus 엔드포인트(`http://localhost:8081/actuator/prometheus`)에서 수집된 실제 메트릭이다:

```prometheus
# HELP secureflow_detection_alerts_duplicates_total fingerprint로 중복 제거된 경보 수
# TYPE secureflow_detection_alerts_duplicates_total counter
secureflow_detection_alerts_duplicates_total{application="secureflow"} 0.0

# HELP secureflow_detection_event_to_alert_latency_seconds 마지막 원인 이벤트에서 경보 생성까지 걸린 시간
# TYPE secureflow_detection_event_to_alert_latency_seconds histogram
secureflow_detection_event_to_alert_latency_seconds_count{application="secureflow"} 61
secureflow_detection_event_to_alert_latency_seconds_sum{application="secureflow"} 29566.279460816
secureflow_detection_event_to_alert_latency_seconds_max{application="secureflow"} 504.499846759
```

| 지표 | 실측치 | 의미 및 분석 |
| --- | --- | --- |
| **생성된 경보 수 (`alerts_created_count`)** | **61건** | 10분 윈도우 내 다계정 공격 기준 충족 경보 |
| **중복 제거 경보 수 (`duplicates_total`)** | **0건** | 워터마크 전진으로 중복 경보 생성 방지 |
| **누적 지연 시간 합계 (`latency_seconds_sum`)** | 29,566.28초 | 61건 경보의 전체 지연 시간 합산 |
| **평균 Event-to-Alert 지연** | **484.69초 (약 8분 5초)** | 원인 이벤트 발생부터 경보 저장까지의 평균 지연 |
| **최대 Event-to-Alert 지연 (`latency_seconds_max`)** | **504.50초 (약 8분 24초)** | 10분 윈도우의 앞쪽 이벤트에 대한 최대 지연 |

> **분석**: 이 Timer는 각 탐지 후보의 `lastEventAt`부터 경보의 `createdAt`까지 측정한다. 이번 synthetic 데이터 분포에서는 평균 8분 5초, 최대 8분 24초였다. 따라서 현재 10분 tumbling window를 실시간 탐지로 표현해서는 안 된다. 2분 이하 SLO가 필요하다면 더 짧은 window만 적용하기보다 sliding/incremental 처리와 late-event 재처리 정책을 함께 설계해야 한다.

---

## 7. 종합 결론 및 권장사항

1. **대규모 데이터 처리 역량**:
   - 100만 건 데이터셋을 500 단위 배치로 적재할 때 초당 **3,118건**을 처리하여 5분 20초 만에 안정적으로 완료되었다.
2. **부하 테스트 통과**:
   - 이벤트 수집 50 RPS 부하에서 p95 206ms, 실패율 0.00% 달성.
   - 100만 건 대상 이벤트 검색 30 RPS 부하에서 p95 140ms, p99 209ms 달성.
3. **인덱스 및 쿼리 실행 계획 검증**:
   - 버퍼 캐시가 준비된 측정 시점에서 표본 검색·집계 쿼리 3종은 **10ms 미만(0.45ms~2.56ms)**이었다. 이는 해당 데이터 분포와 시간 구간에서 올바른 인덱스가 선택됐다는 증거이며, 모든 쿼리와 운영 환경의 지연을 보장하는 수치는 아니다.
4. **운영 환경 권장사항**:
   - 더 빠른 실시간 경보가 필요한 경우 `windowSize`를 5분으로 단축하거나 sliding window/incremental rule을 도입할 수 있다.
   - 수천만 건 또는 장기 보존 요구가 확인되면 `occurred_at` 기준 파티셔닝과 보존 정책을 별도 벤치마크한 뒤 적용한다.

## 8. 측정 해석의 한계

- Apple M4 개발 장비의 단일 Docker 환경에서 한 차례 수행한 결과다.
- 별도의 CPU·메모리 제한을 둔 운영 환경 결과가 아니다.
- 데이터는 synthetic 분포이며 실제 고객별 편향과 payload 다양성을 대표하지 않는다.
- EXPLAIN ANALYZE는 버퍼 캐시가 준비된 상태의 표본 구간 결과다. cold-cache 결과와 반복 실행 분산은 측정하지 않았다.
- k6 임계값 통과는 이 개발 환경의 포트폴리오 기준을 만족했다는 뜻이며 운영 SLO를 확정한 것이 아니다.
- Event-to-Alert 평균 약 8분은 현재 tumbling window 설계의 비용을 보여준다. 낮은 지연 요구가 생기면 탐지 방식을 재설계해야 한다.

따라서 이 보고서는 “100만 건 개발 데이터에서 현재 설계가 재현 가능하게 동작했다”는 근거로 사용하고, 실서비스 용량 산정 자료로 직접 사용하지 않는다.
