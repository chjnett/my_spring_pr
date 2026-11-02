# 탐지 Window, Watermark와 경보 낙관적 락

## event time과 processing time

- event time: 실제 사건이 발생한 `occurred_at`
- processing time: SecureFlow가 이벤트를 수집하거나 탐지한 시각

네트워크 지연이나 재시도 때문에 두 시각은 다를 수 있다. 탐지 규칙은 사건의 순서를 의미하는 event time을 기준으로 집계하고, ingestion grace만큼 기다린 뒤 구간을 확정한다.

## 반개방 구간 `[from, to)`

인접 구간을 `[00:00, 00:10)`, `[00:10, 00:20)`로 정의하면 정확히 00:10 이벤트는 두 번째 구간에만 포함된다. 양쪽을 모두 포함하면 경계 이벤트가 중복되고, 양쪽을 모두 제외하면 누락된다.

## watermark가 보장하는 것

watermark는 “이 시각 이전 구간을 성공적으로 처리했다”는 진행 위치다. SecureFlow는 다음 순서를 지킨다.

1. watermark 행을 `FOR UPDATE`로 잠근다.
2. 고정 window가 완성됐는지 확인한다.
3. JDBC 집계와 경보 생성을 수행한다.
4. 모두 끝난 뒤 watermark를 구간 끝으로 갱신한다.

실패 전에 watermark부터 이동하면 이벤트를 영구히 건너뛸 수 있다. 반대로 경보 생성 후 watermark 이동이 실패하면 같은 구간을 다시 처리하지만 fingerprint 멱등성이 중복을 막는다.

watermark는 데이터가 완전하다는 수학적 보장이 아니다. grace보다 늦게 온 이벤트가 없다는 운영 가정이 들어간다.

## fingerprint와 멱등성

현재 fingerprint의 입력은 다음과 같다.

```text
rule | subject | windowStart | windowEnd
```

SHA-256은 값을 고정 길이로 만들지만 중복 방지의 진짜 보장은 DB UNIQUE 제약이다. 사전 `exists` 조회만 사용하면 두 탐지 인스턴스가 동시에 “없음”을 보고 둘 다 INSERT할 수 있다.

fingerprint 설계는 제품 의미를 결정한다. window를 포함하면 같은 사용자가 다음 window에서 다시 임계값을 넘을 때 새 경보가 생성된다. 반대로 subject만 넣으면 한번 생성된 뒤 영원히 새 경보를 만들 수 없다.

## JDBC 집계와 JPA Aggregate

탐지 쿼리는 많은 이벤트를 GROUP BY하고 HAVING으로 줄이는 작업이라 JDBC가 명시적이고 효율적이다. 결과 후보가 수십 건으로 줄어든 뒤에는 JPA Aggregate가 상태 전이와 version을 관리한다.

```text
수많은 security_events --JDBC 집계--> DetectionCandidate
                                      |
                                      +--JPA--> SecurityAlert Aggregate
```

한 트랜잭션 안에서 모든 후보를 저장하면 한 fingerprint 충돌이 전체 batch를 롤백할 수 있다. 경보 생성은 `REQUIRES_NEW`로 후보별 트랜잭션을 만들고 중복만 격리한다.

## `@Version`이 막는 lost update

두 분석가가 version 0 경보를 동시에 읽고 서로 다른 담당자를 저장하면 마지막 UPDATE가 앞의 결과를 덮을 수 있다. Hibernate는 다음과 같은 조건을 추가한다.

```sql
update security_alerts
set assignee_id = ?, version = 1
where id = ? and version = 0
```

첫 UPDATE 이후 version이 1이므로 두 번째 UPDATE의 영향 행은 0건이다. 이를 충돌로 해석해 `409`로 돌려주면 사용자는 최신 상태를 다시 조회할 수 있다.

낙관적 락은 충돌을 자동으로 합치지 않는다. 어떤 필드는 병합 가능한지, 재시도해도 되는지 제품 규칙이 필요하다.

## 직접 확인할 코드

- `DetectionQueryRepository`: 네 가지 JDBC GROUP BY/HAVING
- `DetectionWatermarkService`: 구간 잠금과 성공 후 watermark 이동
- `AlertCreationService`: fingerprint와 후보별 새 트랜잭션
- `SecurityAlert`: 상태 전이와 `@Version`
- `DetectionMetrics`: 생성·중복·이벤트→경보 지연
- `RuleDetectionPostgresTest`: 임계값·경계·중복·API·동시성

## 추가 실습

1. window 경계 앞 4건, 뒤 1건을 넣어 tumbling window false negative를 재현한다.
2. 1분 간격 sliding window를 구현하고 중복 경보 suppression을 설계한다.
3. 탐지 중 예외를 강제로 발생시켜 watermark가 이동하지 않는지 확인한다.
4. 두 탐지 인스턴스가 같은 watermark를 처리할 때 행 잠금 대기 시간을 측정한다.
5. 100만 이벤트에서 각 규칙의 `EXPLAIN (ANALYZE, BUFFERS)`를 비교한다.

## 확인 질문

1. watermark를 탐지 전에 갱신하면 어떤 장애에서 이벤트가 유실되는가?
2. SHA-256 fingerprint만 있고 UNIQUE 제약이 없으면 동시 실행 중복을 막지 못하는 이유는 무엇인가?
3. tumbling window와 sliding window의 비용·정확도 차이는 무엇인가?
4. 경보 생성에 후보별 트랜잭션을 사용하는 이유는 무엇인가?
5. `@Version` 충돌을 무조건 자동 재시도하면 사용자의 의도를 덮을 수 있는 이유는 무엇인가?
