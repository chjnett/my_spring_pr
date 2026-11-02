# 스터디

기본 개념을 "알고 있다"가 아니라 코드와 테스트로 설명할 수 있는 상태로 만드는 문서다.

| 순서 | 문서 | 완료 기준 |
| --- | --- | --- |
| 01 | [Spring Boot와 데이터베이스 기본기](01-spring-boot-basics.md) | Controller·Service·Repository·DTO·Transaction의 책임을 설명한다. |
| 06 | [멱등성 키 관측](06-idempotency-observability.md) | 멱등성 결과 메트릭과 고카디널리티 위험을 설명한다. |
| 07 | [권한 요청 상태 머신과 낙관적 락](07-access-request-state-and-optimistic-locking.md) | 상태 전이·`@Version`·flush와 commit 차이를 설명한다. |
| 08 | [멱등성 선점과 최초 응답 재생](08-idempotency-reservation-and-response-replay.md) | 원자적 선점·request hash·응답 snapshot의 필요성을 설명한다. |
| 09 | [Spring Security principal과 RBAC](09-spring-security-principal-and-rbac.md) | 인증·인가·principal·authority·BCrypt의 책임을 설명한다. |
| 10 | [Transactional Outbox와 SKIP LOCKED](10-transactional-outbox-and-skip-locked.md) | dual-write·행 잠금 선점·재시도 멱등성을 장애 시점으로 설명한다. |
| 11 | [Correlation ID와 MDC](11-correlation-id-and-mdc.md) | 요청 추적 ID의 검증·ThreadLocal 정리·감사 이벤트 연결을 설명한다. |
| 12 | [Outbox 재시도와 Dead Letter](12-outbox-retry-and-dead-letter.md) | 이벤트 단위 트랜잭션·지수 backoff·poison event 격리·운영 메트릭을 설명한다. |

스터디 문서는 문제 해결 결과를 과장하지 않는다. 구현 전 알아야 할 개념, 실습 범위, 확인 질문을 관리한다.
