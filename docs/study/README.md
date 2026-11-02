# 스터디

기본 개념을 "알고 있다"가 아니라 코드와 테스트로 설명할 수 있는 상태로 만드는 문서다.

| 순서 | 문서 | 완료 기준 |
| --- | --- | --- |
| 01 | [Spring Boot와 데이터베이스 기본기](01-spring-boot-basics.md) | Controller·Service·Repository·DTO·Transaction의 책임을 설명한다. |
| 06 | [멱등성 키 관측](06-idempotency-observability.md) | 멱등성 결과 메트릭과 고카디널리티 위험을 설명한다. |
| 07 | [권한 요청 상태 머신과 낙관적 락](07-access-request-state-and-optimistic-locking.md) | 상태 전이·`@Version`·flush와 commit 차이를 설명한다. |
| 08 | [멱등성 선점과 최초 응답 재생](08-idempotency-reservation-and-response-replay.md) | 원자적 선점·request hash·응답 snapshot의 필요성을 설명한다. |

스터디 문서는 문제 해결 결과를 과장하지 않는다. 구현 전 알아야 할 개념, 실습 범위, 확인 질문을 관리한다.
