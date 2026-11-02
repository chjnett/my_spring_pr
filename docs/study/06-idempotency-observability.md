# 멱등성 키 관측: Prometheus와 Grafana

동시성 테스트는 한 번의 정합성 증거를 남긴다. Grafana는 실제 API 요청을 여러 번 보내면서 결과 비율과 추이를 보는 데 사용한다.

## 실행 순서

1. 애플리케이션을 실행한다.

   ```bash
   ./gradlew bootRun
   ```

2. 관측 도구를 실행한다.

   ```bash
   docker compose up -d prometheus grafana
   ```

3. `http://localhost:3001`에서 Grafana에 로그인한다.
   - user: `admin`
   - password: `secureflow`

4. `SecureFlow / Idempotency Key` 대시보드를 연다.

## 관찰할 메트릭

`secureflow_idempotency_requests_total`은 결과별 카운터다.

| outcome | 뜻 |
| --- | --- |
| `created` | 처음 처리한 요청 |
| `replayed` | 같은 키·같은 본문의 재시도 |
| `in_progress` | 아직 최초 요청이 끝나지 않은 재시도 |
| `key_reused` | 같은 키로 다른 본문을 보낸 요청 |
| `duplicate_role_grant` | 다른 키로 이미 있는 권한을 요청한 경우 |

`Idempotency-Key` 원문은 metric label에 넣지 않는다. 값마다 새로운 시계열이 생기므로, 운영에서는 메모리와 쿼리 비용을 키울 수 있다.

## 실행 결과 (2026-10-29)

개발 PostgreSQL에 실제 요청을 보낸 뒤 Prometheus가 수집한 값이다. 단위 테스트의 결과가 아니라, 실행 중인 애플리케이션의 `/actuator/prometheus`에서 확인했다.

| 요청 시나리오 | HTTP 응답 | outcome | 관측값 |
| --- | --- | --- | --- |
| 최초 `ADMIN` 권한 부여 | `201 Created` | `created` | 1 |
| 같은 키·같은 본문 재시도 | `201 Created` + `Idempotency-Replayed: true` | `replayed` | 1 |
| 같은 키·다른 본문 | `422 Unprocessable Entity` | `key_reused` | 1 |
| 다른 키·이미 있는 `ADMIN` 요청 | `409 Conflict` | `duplicate_role_grant` | 1 |

`in_progress`는 최초 요청이 끝나기 전에 같은 키가 들어오는 경쟁 상황에서만 증가한다. 빠른 단일 요청으로는 재현되지 않으므로, 다음 실습에서는 테스트용 지연 지점을 두고 동시 요청으로 검증한다.

## 검증 질문

1. 같은 키를 두 번 보내면 `created`와 `replayed`는 각각 어떻게 변하는가?
2. 다른 키로 같은 역할을 요청하면 어떤 outcome이 증가하는가?
3. 처리 중 상태를 의도적으로 길게 만들면 왜 `in_progress`가 필요할까?
