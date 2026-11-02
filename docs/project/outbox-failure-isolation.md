# Outbox 실패 격리·재시도·Dead Letter 구현 결과

## 구현 목표

기존 Publisher는 한 배치를 하나의 트랜잭션으로 처리했다. 이벤트 한 건의 적재가 실패하면 앞서 처리한 정상 이벤트까지 롤백되므로, 같은 poison event가 배치 앞에 계속 남아 전체 파이프라인을 막을 수 있었다.

이번 구현은 실패 범위를 이벤트 한 건으로 줄이고, 재시도 한도를 넘긴 이벤트를 명시적으로 격리한다.

## 처리 흐름

```text
PENDING + due
  → FOR UPDATE SKIP LOCKED로 한 건 선점
  → security_events INSERT + PUBLISHED 갱신
  → 성공: 독립 트랜잭션 COMMIT
  → 실패: 해당 트랜잭션만 ROLLBACK
      → attempt_count 증가
      → 지수 backoff 후 PENDING 유지
      → max_attempts 도달 시 FAILED + audit_outbox_dead_letter 복사
```

`AuditOutboxPublisher`는 배치 크기만큼 이 단위를 반복한다. 한 이벤트가 실패해도 실패 기록 트랜잭션을 끝낸 다음 다음 due 이벤트를 계속 조회한다.

## 스키마

Flyway V8은 `audit_outbox`에 다음 운영 상태를 추가한다.

| 컬럼 | 의미 |
| --- | --- |
| `attempt_count` | 누적 발행 실패 횟수 |
| `next_attempt_at` | 다음 재시도가 가능한 시각 |
| `last_error` | 공백을 정규화하고 1,000자로 제한한 마지막 원인. SQL 오류는 민감한 row detail 대신 예외 유형과 SQLSTATE만 저장 |
| `failed_at` | 최대 시도 횟수 초과 시각 |
| `status=FAILED` | 자동 발행 대상에서 제외된 최종 실패 상태 |

`audit_outbox_dead_letter`는 실패 당시 원본 payload와 식별자, 시도 횟수, 오류, 시각을 복사한다. `event_id` UNIQUE로 같은 이벤트가 여러 번 격리되는 것을 막는다.

## 재시도 정책

기본값은 최대 5회, 1초부터 시작하는 지수 backoff, 최대 5분이다.

```text
delay = min(baseBackoff × 2^(attemptCount-1), maxBackoff)
```

환경 변수로 조정할 수 있다.

```bash
OUTBOX_PUBLISHER_BATCH_SIZE=100
OUTBOX_PUBLISHER_MAX_ATTEMPTS=5
OUTBOX_PUBLISHER_BASE_BACKOFF=1s
OUTBOX_PUBLISHER_MAX_BACKOFF=5m
```

## 관측 지표

| Micrometer 이름 | Prometheus 이름 | 의미 |
| --- | --- | --- |
| `secureflow.outbox.published` | `secureflow_outbox_published_total` | 발행 성공 누계 |
| `secureflow.outbox.failures` | `secureflow_outbox_failures_total` | 발행 시도 실패 누계 |
| `secureflow.outbox.retries` | `secureflow_outbox_retries_total` | 재시도 예약 누계 |
| `secureflow.outbox.dead.letters` | `secureflow_outbox_dead_letters_total` | DLQ 격리 누계 |
| `secureflow.outbox.pending` | `secureflow_outbox_pending` | 현재 backlog |
| `secureflow.outbox.oldest.pending.age` | `secureflow_outbox_oldest_pending_age_seconds` | 최고 대기 시간 |
| `secureflow.outbox.dead.letter.count` | `secureflow_outbox_dead_letter_count` | 현재 DLQ 건수 |

Grafana의 `SecureFlow · Transactional Outbox` 대시보드에는 backlog, 최고 지연, DLQ, 실패와 처리 결과 추이를 배치했다.

## 실패 재현 증거

`TransactionalOutboxPostgresTest`는 PostgreSQL trigger로 특정 `aggregate_id=poison`의 `security_events` INSERT만 강제로 실패시킨다.

- poison event는 두 번 실패한 뒤 `FAILED`가 된다.
- 동일 이벤트는 Dead Letter에 한 건만 저장된다.
- 뒤에 있던 정상 이벤트는 같은 `publishBatch` 호출에서 `PUBLISHED`가 된다.
- poison event는 `security_events`에 남지 않는다.
- published 1, failures 2, retry 1, dead-letter 1의 메트릭 증가를 검증한다.
- 전체 회귀 테스트 54개가 실패·오류·스킵 없이 통과한다.

## 남은 운영 과제

- DLQ 조회·재처리 API는 아직 제공하지 않는다. 재처리는 원인 제거와 운영자 승인 후 별도 명령으로 설계해야 한다.
- Compose에서는 DB role 차원의 `security_events` UPDATE/DELETE 차단을 적용했다. 실제 배포 환경에도 동일한 migrator/application 계정 분리를 적용해야 한다.
- 다중 HMAC key ring은 구현했으며 실제 KMS/Secret Manager 연동과 회전 훈련이 필요하다.
- 최고 대기 시간 60초와 DLQ 1건 이상에 대한 Prometheus alert rule을 추가해야 한다.
