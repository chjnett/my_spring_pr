# JDBC 배치 적재와 커서 페이지네이션

## 이번 코드에서 공부할 핵심

SecureFlow는 비즈니스 Aggregate에는 JPA를, 많은 이벤트를 한 번에 넣고 고정된 조회 조건으로 읽는 경로에는 JDBC를 사용한다. 어느 기술이 더 우수해서가 아니라 작업의 형태가 다르기 때문이다.

- JPA: 상태 전이, 연관관계, dirty checking, Aggregate 트랜잭션
- JDBC: 명시적인 SQL, 배치 적재, PostgreSQL 전용 잠금·인덱스·실행 계획

## `batchUpdate`와 부분 오류

DB batch에 잘못된 값을 그대로 섞으면 한 행의 제약 위반이 전체 JDBC batch와 트랜잭션을 실패시킬 수 있다. 이번 구현은 먼저 모든 항목을 애플리케이션에서 정규화·검증하고 통과한 항목만 batch argument로 만든다.

```text
요청 500건
  → 항목별 allowlist/형식 검증
  → 거부 항목은 결과 배열에 기록
  → 통과 항목만 batchUpdate
  → update count 1 = ACCEPTED, 0 = DUPLICATE
```

이 방식의 부분 성공은 계약에 명시해야 한다. 호출자는 HTTP 202만 보고 전체 성공으로 판단하지 말고 각 결과를 확인해야 한다. DB 장애처럼 batch 자체가 실패하면 트랜잭션이 롤백되므로 항목 오류와 시스템 오류도 구분된다.

## prepared statement가 해결하는 것

사용자 값은 SQL 문자열에 이어 붙이지 않고 `?`에 바인딩한다. 따라서 `x'); drop table ...` 같은 값도 SQL 구조가 아니라 값으로 처리된다.

하지만 바인딩만으로 모든 동적 SQL이 안전해지는 것은 아니다. 컬럼명, 정렬 방향, 연산자를 요청에서 받아 문자열로 붙이면 여전히 위험하다. SecureFlow는 코드가 정한 필터 절만 추가하고 event type·outcome도 enum allowlist로 제한한다.

## offset 대신 커서를 쓴 이유

`OFFSET 100000 LIMIT 50`은 앞의 10만 행을 찾아 버려야 한다. 조회 사이 새 행이 추가되면 페이지 경계가 움직여 중복이나 누락도 생길 수 있다.

커서는 마지막 행의 정렬 키를 기억한다.

```sql
where occurred_at < :lastTime
   or (occurred_at = :lastTime and id < :lastId)
order by occurred_at desc, id desc
limit :sizePlusOne
```

`occurred_at` 하나만 쓰면 같은 시각의 이벤트 순서가 불안정하다. 단조 증가하는 `id`를 tie-breaker로 더해 전체 순서를 만든다. `size + 1`건을 조회해 다음 페이지 존재 여부를 판단하고, 실제 응답에서는 마지막 한 건을 제외한다.

커서는 암호화된 권한 토큰이 아니다. 현재 코드는 URL-safe Base64로 직렬화하고 형식을 검증할 뿐이다. 값 노출이나 조작을 막아야 한다면 만료 시각과 필터 hash를 포함해 서버 secret으로 서명해야 한다.

## 인덱스를 읽는 법

검색 조건과 정렬 순서가 복합 인덱스 순서에 반영되어야 한다.

```text
(source_ip, occurred_at DESC, id DESC)
```

이 인덱스는 특정 IP의 최신 이벤트를 읽는 데 유리하다. 반대로 IP 없이 actor만 검색할 때는 이 인덱스의 첫 컬럼을 건너뛰므로 적합하지 않다. 모든 필터 조합마다 인덱스를 만들면 쓰기 비용과 저장 공간이 커지므로 실제 query 빈도와 selectivity를 측정해 결정해야 한다.

테스트에서 `enable_seqscan=off`를 쓰는 것은 작은 테스트 테이블에서도 후보 인덱스가 SQL에 맞는지 확인하기 위한 장치다. 운영 성능 증거는 충분한 데이터에서 실제 실행 시간, actual rows, buffer hit/read를 함께 봐야 한다.

## 직접 확인할 코드

- `SecurityEventIngestService`: 검증된 항목의 `batchUpdate`와 update count 해석
- `SecurityEventNormalizer`: allowlist, IP·시각 정규화, 민감 키 차단
- `SecurityEventQueryService`: 고정 SQL 조각, 파라미터 바인딩, seek 조건
- `SecurityEventCursorCodec`: 복합 커서 인코딩·검증
- `V9__extend_security_events_for_ingestion_and_search.sql`: 검색 인덱스
- `SecurityEventIngestionPostgresTest`: 부분 성공, injection, 인증, 실행 계획

## 추가 실습

1. 100만 건 seed 후 offset과 cursor의 p50/p95를 비교한다.
2. `EXPLAIN (ANALYZE, BUFFERS)`에서 estimated rows와 actual rows 차이를 기록한다.
3. 동일 `occurred_at` 이벤트 100건을 만들고 페이지 크기 7로 전부 순회한다.
4. cursor에 filter hash와 HMAC을 추가해 다른 검색 조건에서 재사용하지 못하게 한다.
5. source별 credential 테이블을 설계하되 원문 API key 대신 단방향 hash를 저장한다.

## 확인 질문

1. 항목 검증 없이 JDBC batch를 실행하면 부분 성공 계약이 어려운 이유는 무엇인가?
2. `ON CONFLICT DO NOTHING`과 사전 중복 조회의 동시성 차이는 무엇인가?
3. prepared statement를 사용해도 동적 정렬 컬럼이 위험할 수 있는 이유는 무엇인가?
4. 커서에 `id`가 없으면 같은 발생 시각에서 어떤 문제가 생기는가?
5. 복합 인덱스의 첫 컬럼이 검색 조건에 없을 때 효율이 떨어지는 이유는 무엇인가?
