# Correlation ID와 MDC

## 학습 목표

- 여러 로그와 감사 이벤트를 하나의 HTTP 요청 단위로 연결한다.
- 클라이언트가 보낸 추적 ID를 그대로 신뢰하면 안 되는 이유를 설명한다.
- MDC의 ThreadLocal 성격과 비동기 전파 한계를 설명한다.
- Correlation ID도 감사 이벤트 HMAC 범위에 포함해야 하는 이유를 설명한다.

## 요청 추적 문제

한 번의 HTTP 요청은 Controller, Service, JPA, Outbox Publisher를 지나며 여러 로그와 DB 행을 만든다. 시간만으로 연결하면 동시 요청이 많을 때 어떤 로그가 같은 요청인지 판단하기 어렵다.

```text
HTTP request
  ├─ application log
  ├─ role_grant_requests
  ├─ audit_outbox
  └─ security_events
```

같은 `correlation_id`를 남기면 위 결과를 하나의 흐름으로 검색할 수 있다.

## 안전한 입력 정책

SecureFlow는 `X-Correlation-ID` 헤더를 받되 다음 형식만 유지한다.

```text
[A-Za-z0-9][A-Za-z0-9._-]{0,63}
```

헤더가 없거나 공백·제어문자·경로 형태 등 허용 범위를 벗어나면 서버가 UUID를 새로 만든다. 이 제한은 로그 위조, 줄바꿈 삽입과 고카디널리티 값의 무제한 크기를 줄인다.

Correlation ID는 인증 정보가 아니다. 같은 값을 안다는 이유로 권한을 주거나 사용자 신원을 판단하면 안 된다. 클라이언트가 같은 안전한 값을 반복해서 보낼 수도 있다.

## MDC

SLF4J MDC는 현재 실행 Thread에 key/value를 연결한다. 필터가 요청 시작 시 `correlationId`를 넣으면 같은 Thread에서 실행되는 로그 패턴이 값을 읽을 수 있다.

반드시 `finally`에서 제거해야 한다. 서버 Thread pool은 Thread를 재사용하므로 제거하지 않으면 다음 사용자의 로그에 이전 요청 ID가 남을 수 있다.

```text
MDC.put → filter chain → finally → MDC.remove
```

`@Async`, 별도 Executor, 메시지 큐처럼 Thread가 바뀌는 경계에서는 MDC가 자동 전달되지 않는다. 이런 경계는 correlation ID를 작업 데이터에 명시적으로 포함하거나 TaskDecorator로 복사해야 한다.

## 감사 이벤트와 HMAC v2

Correlation ID만 DB 컬럼에 추가하고 서명 대상에서 제외하면 DB를 수정할 수 있는 사람이 이벤트를 다른 요청에 연결해 조사 흐름을 왜곡할 수 있다. SecureFlow HMAC v2는 기존 필드 뒤에 correlation ID를 추가해 함께 서명한다.

- v1 또는 version이 없는 기존 이벤트: 기존 canonical 형식으로 검증
- v2 이벤트: 기존 필드와 correlation ID를 함께 검증
- 알 수 없는 version: `UNKNOWN_VERSION`

서명 버전을 저장하면 새 필드를 추가한 뒤에도 과거 이벤트를 당시 규칙으로 검증할 수 있다.

## 실습과 확인 질문

1. 안전한 `X-Correlation-ID`와 잘못된 값을 각각 보내 응답 헤더를 비교한다.
2. 같은 correlation ID로 여러 요청을 보내면 왜 보안상 동일 요청이라고 단정할 수 없는지 설명한다.
3. 필터의 `finally` 제거 코드를 없애고 Thread 재사용 시나리오를 작성한다.
4. `security_events.correlation_id`만 변경했을 때 HMAC 검증이 `INVALID`인지 확인한다.
5. Outbox Publisher는 다른 Thread에서 동작하는데도 correlation ID가 유지되는 이유를 설명한다.
