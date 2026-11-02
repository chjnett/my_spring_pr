# Transactional Outbox와 `SKIP LOCKED`

## 학습 목표

이 문서를 읽은 뒤 다음 질문에 코드와 트랜잭션 시점으로 답할 수 있어야 한다.

- 왜 DB 커밋 후 메시지를 보내는 방식은 감사 로그 유실 가능성이 있는가?
- Outbox는 무엇을 원자적으로 만들고, 무엇까지 보장하지는 않는가?
- `FOR UPDATE SKIP LOCKED`가 여러 Worker의 작업을 어떻게 나누는가?
- `at-least-once` 재시도에서 왜 소비 측 멱등성이 필요한가?

## 1. Dual-write 문제

하나의 요청에서 PostgreSQL과 메시지 브로커처럼 서로 다른 시스템 두 곳을 변경하면 일반적인 로컬 트랜잭션 하나로 묶을 수 없다.

```text
DB COMMIT ── 성공
메시지 전송 ── 실패
```

순서를 반대로 바꾸어도 메시지는 전송됐지만 DB가 롤백되는 반대 문제가 생긴다. `try/catch`나 단순 재시도만으로 두 시스템의 원자성을 만들 수는 없다.

## 2. Outbox가 바꾸는 경계

비즈니스 데이터와 “나중에 발행할 이벤트”를 같은 DB에 저장하면 두 INSERT를 하나의 로컬 트랜잭션으로 묶을 수 있다.

```text
BEGIN
  UPDATE role_grant_requests ...
  INSERT role_grants ...
  INSERT audit_outbox ... status = 'PENDING'
COMMIT
```

이 트랜잭션이 실패하면 세 변경이 모두 사라지고, 성공하면 세 변경이 모두 남는다. 다만 Outbox 자체가 외부 전달을 exactly-once로 만들어 주지는 않는다. Publisher가 커밋 결과를 확실히 알 수 없는 장애가 있으므로 재시도와 중복 방지가 필요하다.

## 3. `FOR UPDATE SKIP LOCKED`

일반 `FOR UPDATE`는 다른 Worker가 잠근 행이 풀릴 때까지 기다린다. 작업 큐에서는 기다리는 대신 아직 잠기지 않은 다른 일을 처리하는 편이 낫다.

```sql
select id, event_id, payload
from audit_outbox
where status = 'PENDING'
order by id
limit 100
for update skip locked;
```

- `FOR UPDATE`: 선택한 행에 쓰기 잠금을 건다.
- `SKIP LOCKED`: 이미 다른 트랜잭션이 잠근 행은 결과에서 제외한다.
- 트랜잭션 종료: 커밋 또는 롤백과 함께 잠금이 해제된다.

이 쿼리는 일반 검색 API에 쓰면 안 된다. 잠긴 행이 결과에서 조용히 사라지므로 사용자가 보는 목록의 완전성을 깨뜨릴 수 있다. 경쟁 Worker가 작업을 분배하는 큐 성격의 조회에 적합하다.

## 4. 재시도와 멱등성

Publisher가 이벤트를 INSERT한 뒤 결과를 받기 전에 연결이 끊기면 호출자는 성공 여부를 확신할 수 없다. 이때 같은 `event_id`로 다시 시도할 수 있어야 한다.

SecureFlow는 다음 두 장치를 사용한다.

```sql
constraint uk_security_events_event_id unique (event_id)
```

```sql
insert into security_events (...)
values (...)
on conflict (event_id) do nothing;
```

UNIQUE 제약은 동시 요청까지 포함한 최종 정합성 장치이고, `ON CONFLICT`는 중복을 정상적인 재시도 결과로 처리하는 정책이다.

## 5. JPA와 JDBC를 함께 쓰는 이유

JPA는 Aggregate 상태 전이와 dirty checking을 표현하기 좋다. 반면 작업 큐 선점, PostgreSQL 전용 잠금 문법, 배치 INSERT는 SQL을 명시하는 JDBC가 더 직접적이다.

중요한 기준은 “한 프로젝트에서는 하나만 써야 한다”가 아니라 각 기술의 경계와 트랜잭션 의미를 분명히 하는 것이다.

- 비즈니스 쓰기 + Outbox 생성: JPA
- Outbox 잠금 선점 + 이벤트 적재: JDBC
- 스키마와 UNIQUE/인덱스: Flyway SQL

## 6. 장애 시점 확인 문제

다음 시점마다 최종 상태를 직접 그려 본다.

1. 비즈니스 상태 저장 전 예외
2. Outbox INSERT 후 비즈니스 트랜잭션 커밋 전 종료
3. Publisher가 행을 선점한 직후 종료
4. `security_events` INSERT 후 Publisher 커밋 전 종료
5. Publisher 커밋 직후 클라이언트가 응답을 받기 전 연결 종료

1~4는 해당 트랜잭션이 롤백된다. 5는 이미 커밋됐을 수 있으므로 재시도 시 `event_id` 멱등성이 필요하다.

## 실습

1. `AuditOutboxPublisher`에서 `SKIP LOCKED`를 제거하고 두 Publisher 동시 테스트의 대기 시간과 처리 분배를 비교한다.
2. `security_events.event_id` UNIQUE를 제거했을 때 어떤 장애 재현 테스트가 필요한지 작성한다.
3. 한 이벤트의 JSON을 잘못된 값으로 만들어 현재 배치 전체가 롤백되는지 확인하고 poison event 격리 설계를 제안한다.
4. `PENDING` 최장 체류 시간을 Micrometer gauge로 표현하되 `event_id`를 tag로 쓰면 안 되는 이유를 설명한다.
5. payload HMAC을 계산할 canonical JSON 규칙을 설계하고 필드 순서·시간 표현·키 회전 문제를 정리한다.

## 확인 질문

- Outbox와 `security_events`를 같은 DB에 두는 현재 구조의 장점과 장애 도메인 단점은 무엇인가?
- `SKIP LOCKED`만으로 정확히 한 번 처리가 보장되는가?
- 이벤트 INSERT와 Outbox 상태 UPDATE를 서로 다른 트랜잭션으로 나누면 어떤 상태가 생기는가?
- 애플리케이션에 DELETE API가 없다는 사실만으로 append-only 저장소라고 할 수 있는가?
- HMAC은 이벤트 삭제까지 탐지할 수 있는가? 삭제 탐지에는 어떤 연결 구조나 외부 앵커가 필요한가?
