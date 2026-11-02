# SecureFlow 문서 학습 가이드

이 문서는 SecureFlow를 처음 읽는 사람이 많은 문서 사이에서 길을 잃지 않도록 만든 안내서다. 아래 순서를 따르면 **프로젝트 목적 → 전체 구조 → 핵심 기능 → 운영 방법 → 세부 학습 자료**의 흐름으로 이해할 수 있다.

## 1. 먼저 알아둘 프로젝트 한 줄 요약

SecureFlow는 사용자의 권한 변경을 감사 이벤트로 기록하고, 외부 보안 로그를 수집·검색한 뒤 규칙 기반 탐지와 경보 대응까지 수행하는 Spring Boot 보안 운영 플랫폼이다.

일반적인 CRUD 구현보다 다음 문제를 어떻게 해결했는지가 중요하다.

- 동시에 같은 권한을 승인할 때 중복 부여를 어떻게 막는가?
- API가 재시도되어도 같은 데이터가 여러 번 생성되지 않게 하려면 어떻게 해야 하는가?
- 업무 데이터 변경과 감사 로그 기록을 어떻게 하나의 결과로 보장하는가?
- 대량 보안 로그를 어떻게 안전하고 빠르게 저장하는가?
- 저장된 감사 이벤트의 변조를 어떻게 탐지하고 변경 권한을 어떻게 제한하는가?
- 탐지 작업을 다시 실행해도 같은 경보가 중복 생성되지 않게 하려면 어떻게 해야 하는가?

## 2. 권장 문서 읽기 순서

### 1단계: 프로젝트 전체 모습 파악

먼저 [백엔드 포트폴리오 가이드](secureflow/docs/project/backend-portfolio-guide.md)를 읽는다.

이 문서에서는 다음 내용을 파악하면 된다.

- SecureFlow가 해결하려는 문제
- Phase별 구현 범위
- 전체 아키텍처와 데이터 흐름
- 동시성, 멱등성, 감사 무결성에 사용한 기술
- 현재 구현된 범위와 남은 운영 과제

처음 읽을 때 모든 용어를 이해하려고 할 필요는 없다. `Transactional Outbox`, `HMAC`, `낙관적 락`, `watermark`가 각각 어느 문제를 해결하기 위해 등장했는지만 표시해 둔다.

다음 질문에 답할 수 있으면 2단계로 넘어간다.

> 사용자의 권한 변경이 어떻게 보안 이벤트와 경보까지 이어지는가?

### 2단계: 전체 아키텍처 이해

[아키텍처 문서](secureflow/docs/project/architecture.md)를 읽는다.

다음 흐름을 중심으로 본다.

```text
사용자 요청
  → 인증·인가
  → 권한 요청 또는 승인
  → 업무 데이터와 Outbox 저장
  → security_events 발행
  → 탐지 규칙 실행
  → 경보 생성 및 처리
```

읽으면서 각 단계가 어떤 테이블과 서비스 클래스를 사용하는지 연결한다. 세부 클래스 이름을 외우기보다 트랜잭션 경계와 데이터 이동 방향을 이해하는 것이 우선이다.

다음 질문에 답할 수 있으면 3단계로 넘어간다.

> 업무 변경과 감사 이벤트 기록 사이에서 일부만 성공하는 상황을 어떻게 방지하는가?

### 3단계: 인증과 권한 승인 흐름 이해

아래 두 문서를 순서대로 읽는다.

1. [인증·인가 설계](secureflow/docs/project/authentication-and-authorization.md)
2. [접근 권한 요청 구현](secureflow/docs/project/access-request-implementation.md)

중점적으로 볼 내용은 다음과 같다.

- BCrypt 기반 credential 저장
- Spring Security Basic Auth와 역할 기반 접근 통제
- `PENDING → APPROVED | REJECTED` 상태 전이
- 자기 승인과 종료 상태 재처리 차단
- `@Version`을 이용한 낙관적 락
- 동시에 승인 요청이 들어왔을 때 한 건만 성공하는 이유

멱등성 처리가 궁금하면 다음 문서를 이어서 읽는다.

- [접근 요청 멱등성](secureflow/docs/project/access-request-idempotency.md)

다음 질문에 답할 수 있으면 4단계로 넘어간다.

> 같은 권한 요청이나 승인 요청이 동시에 여러 번 들어와도 결과가 한 번만 반영되는 이유는 무엇인가?

### 4단계: 감사 이벤트와 Transactional Outbox 이해

[Transactional Outbox 구현](secureflow/docs/project/transactional-outbox-implementation.md)을 읽는다.

이 문서는 SecureFlow의 핵심 문서 중 하나다. 다음 내용을 중심으로 본다.

- 업무 변경과 `audit_outbox` 저장을 같은 트랜잭션으로 처리하는 이유
- Publisher가 `FOR UPDATE SKIP LOCKED`로 이벤트를 선점하는 방식
- `security_events.event_id` UNIQUE 제약으로 중복 발행을 방지하는 방식
- HMAC 서명과 correlation ID가 저장되는 위치
- 내부 Outbox 이벤트가 HMAC v2를 유지하는 이유
- application DB 계정이 `security_events`를 수정하거나 삭제할 수 없는 이유

장애가 발생한 이벤트를 어떻게 격리하는지는 다음 문서에서 확인한다.

- [Outbox 실패 격리·재시도·Dead Letter](secureflow/docs/project/outbox-failure-isolation.md)

다음 질문에 답할 수 있으면 5단계로 넘어간다.

> 하나의 잘못된 이벤트가 뒤에 있는 정상 이벤트의 발행까지 막지 않는 이유는 무엇인가?

### 5단계: 외부 보안 이벤트 수집과 검색 이해

[보안 이벤트 수집·검색](secureflow/docs/project/security-event-ingestion-search.md)을 읽는다.

다음 내용을 확인한다.

- service credential을 이용한 내부 수집 API 인증
- 입력 이벤트 정규화와 허용 필드 검사
- JDBC batch insert와 항목별 성공·중복·실패 결과
- SQL injection 문자열도 실행하지 않고 데이터로 저장하는 방식
- 기간, event type, actor, IP, outcome 검색
- offset 대신 seek cursor를 사용하는 이유
- HMAC v3이 `source`, `sourceIp`, `outcome`까지 서명하는 이유
- 활성 키와 과거 검증 키를 함께 보유하는 key ring 구조

다음 질문에 답할 수 있으면 6단계로 넘어간다.

> 검색용 컬럼이 DB에서 몰래 변경되었을 때 SecureFlow는 어떻게 변조를 발견하는가?

### 6단계: 규칙 탐지와 경보 처리 이해

[규칙 탐지와 경보 흐름](secureflow/docs/project/rule-detection-and-alert-workflow.md)을 읽는다.

중점적으로 볼 내용은 다음과 같다.

- 로그인 실패 등 보안 이벤트를 일정 시간 구간으로 집계하는 방법
- 고정 window와 ingestion grace
- 성공한 구간까지만 watermark를 이동하는 이유
- fingerprint UNIQUE 제약을 이용한 중복 경보 방지
- `OPEN → INVESTIGATING → RESOLVED` 경보 상태 전이
- 경보 처리에서 낙관적 락을 사용하는 이유

다음 질문에 답할 수 있으면 7단계로 넘어간다.

> 같은 탐지 구간을 다시 실행해도 동일한 경보가 추가로 만들어지지 않는 이유는 무엇인가?

### 7단계: 직접 실행하고 검증하기

[백엔드 전체 구현·운영 가이드](secureflow/docs/project/backend-completion-guide.md)를 읽고 프로젝트를 실행한다.

이 문서에서 확인할 내용은 다음과 같다.

- Docker Compose 실행에 필요한 환경 변수
- PostgreSQL, 애플리케이션, Prometheus, Grafana 구성
- 관리자와 수집 credential 설정
- 주요 API 호출 방법
- 100만 건 데이터 seed 방법
- k6 수집·검색 부하 테스트
- 운영 지표와 현재 설계 한계

실제 실행 기록과 100만 건 대규모 벤치마크 실측 결과는 다음 문서에 있다.

- [라이브 스모크 테스트 기록](secureflow/docs/project/live-smoke-test-2026-11-02.md)
- [100만 건 벤치마크 및 성능 결과](secureflow/docs/project/performance-benchmark-2026-11-02.md)

## 3. 기능과 문서 빠른 연결표

| 궁금한 내용 | 먼저 읽을 문서 |
| --- | --- |
| 프로젝트 전체 설명 | [백엔드 포트폴리오 가이드](secureflow/docs/project/backend-portfolio-guide.md) |
| 전체 요청·데이터 흐름 | [아키텍처](secureflow/docs/project/architecture.md) |
| 로그인과 역할 검사 | [인증·인가](secureflow/docs/project/authentication-and-authorization.md) |
| 권한 신청·승인·거절 | [접근 권한 요청 구현](secureflow/docs/project/access-request-implementation.md) |
| API 중복 요청 방지 | [접근 요청 멱등성](secureflow/docs/project/access-request-idempotency.md) |
| 업무 변경과 감사 기록의 원자성 | [Transactional Outbox](secureflow/docs/project/transactional-outbox-implementation.md) |
| 재시도와 Dead Letter | [Outbox 실패 격리](secureflow/docs/project/outbox-failure-isolation.md) |
| 외부 로그 수집과 검색 | [보안 이벤트 수집·검색](secureflow/docs/project/security-event-ingestion-search.md) |
| 보안 규칙과 경보 | [규칙 탐지·경보](secureflow/docs/project/rule-detection-and-alert-workflow.md) |
| 실행·운영·부하 테스트 | [백엔드 전체 구현·운영 가이드](secureflow/docs/project/backend-completion-guide.md) |
| 100만 건 벤치마크·k6 부하 테스트 | [100만 건 벤치마크 결과](secureflow/docs/project/performance-benchmark-2026-11-02.md) |
| 향후 개발 순서 | [구현 로드맵](secureflow/docs/project/implementation-roadmap.md) |

## 4. 문서를 읽을 때 작성할 메모 양식

각 문서를 읽은 뒤 아래 양식으로 짧게 정리한다.

```text
문서 이름:

1. 해결하려는 문제:
2. 선택한 설계:
3. 핵심 클래스 또는 테이블:
4. 실패·동시성 상황의 처리 방식:
5. 테스트로 증명한 내용:
6. 아직 남아 있는 한계:
7. 이해되지 않는 용어 또는 질문:
```

이 양식을 사용하면 단순히 “어떤 기술을 사용했다”가 아니라 “왜 그 기술이 필요했고 무엇으로 검증했는가”를 설명할 수 있다.

## 5. 코드를 함께 읽는 방법

문서 한 편을 읽은 뒤 관련 코드를 전부 살펴보려고 하지 말고 다음 순서만 따라간다.

```text
Controller 또는 Scheduler
  → Application Service
  → Domain 상태 전이
  → Repository/JDBC SQL
  → Flyway migration
  → 통합 테스트
```

특히 테스트 코드를 마지막에 확인한다. SecureFlow의 핵심 설계는 정상 요청보다 다음과 같은 실패·경쟁 상황에서 더 잘 드러난다.

- 20개의 동시 요청
- 같은 idempotency key 재사용
- Outbox poison event
- 감사 이벤트 필드 변조
- 같은 탐지 window 재실행
- 동일 경보 동시 수정
- application DB 계정의 UPDATE·DELETE 권한 검사

## 6. 면접 또는 발표를 위한 최종 확인 질문

문서를 모두 읽은 뒤 다음 질문에 자신의 말로 답해 본다.

1. 왜 감사 로그를 업무 테이블에 직접 남기지 않고 Outbox를 사용했는가?
2. `FOR UPDATE SKIP LOCKED`는 여러 Publisher 사이에서 어떤 문제를 해결하는가?
3. DB UNIQUE 제약과 애플리케이션 중복 검사는 어떤 차이가 있는가?
4. 낙관적 락이 권한 승인과 경보 처리에 적합한 이유는 무엇인가?
5. HMAC은 암호화와 어떻게 다르며 무엇을 보장하는가?
6. HMAC v2와 v3의 차이는 무엇인가?
7. 키를 회전해도 이전 이벤트를 검증할 수 있는 이유는 무엇인가?
8. application 계정과 Flyway migrator 계정을 분리한 이유는 무엇인가?
9. offset pagination 대신 seek cursor를 사용한 이유는 무엇인가?
10. watermark와 fingerprint가 탐지 작업의 재실행 안정성을 어떻게 보장하는가?
11. 100만 건 규모의 테이블에서 색인 검색과 그룹핑 탐지 쿼리의 실행 계획(EXPLAIN ANALYZE)을 어떻게 최적화했는가?
12. 10분 텀블링 윈도우 환경에서 Event-to-Alert 지연 시간은 어떻게 측정되며 실제 수치는 어떻게 나타나는가?

이 질문에 답할 수 있다면 SecureFlow의 전체 설계와 주요 구현 근거를 파악한 것이다.

## 7. 추가 학습 문서

핵심 흐름을 이해한 뒤 세부 개념을 공부할 때 다음 자료를 사용한다.

- [학습 문서 목록](secureflow/docs/study/README.md)
- [Transactional Outbox와 SKIP LOCKED](secureflow/docs/study/10-transactional-outbox-and-skip-locked.md)
- [Correlation ID와 MDC](secureflow/docs/study/11-correlation-id-and-mdc.md)
- [Outbox 재시도와 Dead Letter](secureflow/docs/study/12-outbox-retry-and-dead-letter.md)
- [JDBC batch와 cursor pagination](secureflow/docs/study/13-jdbc-batch-and-cursor-pagination.md)
- [탐지 window와 watermark](secureflow/docs/study/14-detection-window-watermark-and-optimistic-locking.md)
- [문제 해결 사례 목록](secureflow/docs/case-studies/README.md)
- [트러블슈팅 기록](secureflow/docs/troubleshooting/README.md)

처음에는 2절의 일곱 단계만 따라가고, 추가 학습 문서는 핵심 흐름을 한 번 완주한 뒤 필요한 주제부터 선택해 읽는 것을 권장한다.
