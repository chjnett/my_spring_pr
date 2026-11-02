# Outbox 재시도와 Dead Letter

## 왜 배치 전체를 한 트랜잭션으로 묶으면 안 되는가

Outbox 100건을 한 트랜잭션에서 발행할 때 50번째 이벤트가 실패하면 1~49번째 성공도 롤백된다. 실패 이벤트가 계속 50번째에 남으면 정상 이벤트가 반복해서 처리되고도 커밋되지 않는 head-of-line blocking이 생긴다.

SecureFlow는 이벤트 한 건마다 다음 트랜잭션 경계를 만든다.

1. due 상태의 `PENDING` 한 건을 `FOR UPDATE SKIP LOCKED`로 선점한다.
2. 보안 이벤트 적재와 `PUBLISHED` 갱신을 같은 트랜잭션으로 커밋한다.
3. 실패하면 해당 트랜잭션만 롤백한다.
4. 별도 트랜잭션에서 실패 횟수와 다음 시각을 기록한다.
5. 배치 루프는 다음 due 이벤트를 계속 처리한다.

핵심은 “예외를 잡았다”가 아니라 실패한 데이터의 잠금·롤백·상태 기록 경계를 분리했다는 점이다.

## 지수 backoff가 필요한 이유

DB나 외부 저장소가 30초 동안 장애인데 10ms마다 재시도하면 장애 복구를 돕는 대신 부하를 더한다. 지수 backoff는 실패가 반복될수록 간격을 늘린다.

```text
1초 → 2초 → 4초 → 8초 → … → 최대 5분
```

상한이 없으면 복구 뒤에도 지나치게 늦게 재시도할 수 있으므로 `maxBackoff`를 둔다. 테스트에서는 기다리지 않도록 backoff를 0ms로 설정하지만 운영 기본값과 의미는 다르다.

## `FAILED`와 Dead Letter의 차이

- `audit_outbox.status=FAILED`는 원본 Outbox의 처리 상태다.
- `audit_outbox_dead_letter`는 운영자가 실패 이벤트만 조회·분석할 수 있는 격리 저장소다.

원본을 즉시 삭제하지 않는 이유는 비즈니스 변경과 함께 생성된 감사 근거를 보존하기 위해서다. DLQ 재처리는 단순히 상태를 `PENDING`으로 되돌리는 기능이 아니다. 실패 원인을 제거하고, 중복 가능성을 확인하고, 누가 재처리를 승인했는지 다시 감사해야 한다.

## 메트릭에서 봐야 할 관계

단일 카운터보다 관계가 중요하다.

- `pending` 증가 + `oldest pending age` 증가: Publisher 중단 또는 지속 장애
- `failures_total` 증가 + `retries_total` 증가: 일시 장애 가능성
- `dead_letter_count` 증가: 자동 복구 범위를 벗어난 데이터 오류 또는 지속 장애
- `published_total` 증가 없이 backlog만 증가: ingest 속도가 처리량을 초과하거나 Publisher가 멈춤

이벤트 ID, 사용자 ID, correlation ID를 metric label로 넣으면 카디널리티가 무제한 증가한다. 개별 추적 값은 로그와 DB에 두고, 메트릭 label은 결과·유형처럼 유한한 값만 사용한다.

## 코드에서 확인할 지점

- `AuditOutboxPublisher.publishBatch`: 이벤트 단위 트랜잭션과 실패 후 계속 처리
- `OutboxPublisherProperties.retryDelay`: 지수 backoff와 상한
- `V8__add_outbox_retry_and_dead_letter.sql`: 실패 상태·due 인덱스·DLQ
- `OutboxMetrics`: counter와 gauge의 역할 분리
- `TransactionalOutboxPostgresTest`: trigger 기반 poison event 재현

## 확인 질문

1. 실패 예외를 같은 트랜잭션 안에서 잡고 `attempt_count`를 갱신하면 왜 그 갱신도 함께 롤백될 수 있는가?
2. `SKIP LOCKED`가 있어도 `next_attempt_at` 조건과 인덱스가 필요한 이유는 무엇인가?
3. max attempts를 너무 작게 또는 너무 크게 잡으면 각각 어떤 운영 문제가 생기는가?
4. DLQ 이벤트를 재처리할 때 원본 `event_id`를 유지해야 하는 이유는 무엇인가?
5. backlog 건수는 작지만 oldest age가 큰 상황은 무엇을 의미할 수 있는가?
