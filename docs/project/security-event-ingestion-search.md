# 보안 이벤트 배치 수집·검색 구현 결과

## 결론

SecureFlow Phase 3에서는 외부 서비스가 보안 이벤트를 배치로 전송하고, 운영자가 기간·유형·행위자·IP·결과로 조사할 수 있는 JDBC 경계를 구현했다. 이벤트 적재는 `JdbcTemplate.batchUpdate`, 검색은 바인딩 파라미터와 `(occurred_at, id)` 커서 페이지네이션을 사용한다.

수집 API는 개별 항목을 먼저 검증하므로 한 항목의 오류가 정상 항목을 막지 않는다. 저장은 `event_id` UNIQUE와 `ON CONFLICT DO NOTHING`으로 재전송을 중복으로 판정한다. 검색 API는 ADMIN 또는 AUDITOR만 접근할 수 있다.

## API 계약

### 배치 수집

```http
POST /api/internal/security-events:batch
X-SecureFlow-Ingest-Key: <service credential>
Content-Type: application/json
```

```json
{
  "events": [
    {
      "eventId": "550e8400-e29b-41d4-a716-446655440000",
      "eventType": "LOGIN_FAILED",
      "actorId": 42,
      "sourceIp": "203.0.113.10",
      "outcome": "FAILURE",
      "occurredAt": "2026-11-02T06:30:00Z",
      "attributes": {"reasonCode": "BAD_PASSWORD"}
    }
  ]
}
```

응답은 HTTP `202 Accepted`이며 각 입력 순서에 맞춰 `ACCEPTED`, `DUPLICATE`, `REJECTED`를 반환한다. 부분 오류가 있어도 정상 항목은 저장된다.

허용값은 다음과 같다.

- event type: `LOGIN_SUCCEEDED`, `LOGIN_FAILED`, `ACCESS_DENIED`, `PRIVILEGED_ACTION`
- outcome: `SUCCESS`, `FAILURE`, `DENIED`
- attribute key: `action`, `reasonCode`, `resource`, `result`, `userAgent`
- 배치: 최대 500건, attribute: 최대 10개, 값: 최대 256자
- 발생 시각: 최근 31일부터 서버 시각 기준 미래 5분까지

`password`, `token`, `secret` 같은 키는 금지 목록이 아니라 허용 목록 밖의 모든 키를 거부하는 방식으로 차단한다.

### 이벤트 검색

```http
GET /api/security-events?from=...&to=...&eventType=LOGIN_FAILED&actorId=42&sourceIp=203.0.113.10&outcome=FAILURE&size=50&cursor=...
Authorization: Basic ...
```

- 기본 조회 기간은 최근 24시간, 최대 기간은 31일이다.
- 기본 페이지 크기는 50, 최대는 200이다.
- 정렬은 `occurred_at DESC, id DESC`로 고정한다.
- 다음 페이지는 응답의 `nextCursor`를 그대로 전달한다.
- 임의 SQL 조각을 받지 않고 필터 allowlist와 prepared statement 바인딩만 사용한다.

offset 방식은 앞 페이지에 새 이벤트가 들어오면 중복·누락이 생기고 뒤 페이지로 갈수록 버리는 행이 늘어난다. SecureFlow는 동일 발생 시각을 구분할 `id`까지 커서에 포함한다.

```sql
and (occurred_at < ? or (occurred_at = ? and id < ?))
order by occurred_at desc, id desc
limit ?
```

## 실행 설정

수집 경로는 기본적으로 비활성화되어 있다. 저장소에 실제 키를 넣지 않고 실행 환경에서 주입한다.

```bash
SECURITY_EVENT_INGEST_ENABLED=true \
SECURITY_EVENT_INGEST_SOURCE_ID=identity-service \
SECURITY_EVENT_INGEST_API_KEY='replace-with-a-random-32-character-secret' \
./gradlew bootRun
```

키 비교는 `MessageDigest.isEqual`을 사용한다. 현재 구현은 한 인스턴스에 source와 credential 한 쌍을 설정하는 Phase 3 경계다. 다중 송신자별 키 폐기·회전·사용 이력은 credential registry 또는 Secret Manager 연동으로 확장해야 한다.

## 데이터베이스와 인덱스

Flyway V9는 `security_events`에 `source`, `source_ip`, `outcome`을 추가하고 다음 조회 인덱스를 만든다.

- `(occurred_at DESC, id DESC)`
- `(source_ip, occurred_at DESC, id DESC)`
- `(outcome, occurred_at DESC, id DESC)`

이벤트 유형과 actor 인덱스는 기존 V5 migration의 인덱스를 재사용한다. Testcontainers PostgreSQL에서 sequential scan을 끈 실행 계획으로 IP 검색이 `idx_security_events_source_ip_occurred`를 선택하는지 확인한다. 이 검증은 실제 운영 데이터 분포에서 항상 같은 plan을 보장한다는 뜻은 아니므로 Phase 5의 대량 seed와 `EXPLAIN (ANALYZE, BUFFERS)`로 보완한다.

## 검증 결과

| 시나리오 | 결과 |
| --- | --- |
| 정상·중복·민감 키 포함 3건 배치 | `ACCEPTED` 1, `DUPLICATE` 1, `REJECTED` 1 |
| SQL injection 모양의 attribute 값 | SQL로 실행되지 않고 JSON 문자열 데이터로 저장 |
| service credential 없음 | `401 INVALID_SERVICE_CREDENTIAL` |
| 검색 미인증 | `401` |
| ADMIN 검색 | `200`, 고정 응답 계약 확인 |
| 허용되지 않은 event type | `400 INVALID_SECURITY_EVENT_QUERY` |
| 최대 기간 31일 초과·size 200 초과 | 요청 거부 |
| 동일 시각 가능성이 있는 페이지 이동 | `(occurred_at, id)` 기준 중복·누락 없이 이동 |
| IP 필터 실행 계획 | PostgreSQL에서 복합 인덱스 사용 확인 |
| 외부 이벤트 HMAC | `VALID` |
| 전체 회귀 | 61 tests, 0 failures, 0 errors, 0 skipped |

주요 검증 클래스는 `SecurityEventIngestionPostgresTest`, `SecurityEventCursorCodecTest`다.

## 현재 보안 한계

HMAC payload에는 IP·결과·attribute가 들어가지만 검색용 중복 컬럼인 `source_ip`, `outcome`과 payload의 일치 여부까지 검증하지는 않는다. 따라서 현재 무결성 검증은 서명된 payload 변조를 탐지하지만 DB 권한을 가진 공격자가 검색 컬럼만 바꾸는 상황은 별도 대응이 필요하다. 다음 개선에서는 서명 버전을 올려 검색 컬럼을 canonical data에 직접 포함하고, 구버전 검증 호환성을 유지한다.

또한 API key는 transport 인증 수단일 뿐 전송 구간 암호화가 아니다. 운영에서는 TLS, 네트워크 접근 제어, source별 credential, 주기적 키 회전과 감사 이력이 함께 필요하다.
