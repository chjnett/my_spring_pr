# 현재 프로젝트 구성

이 폴더는 SecureFlow가 무엇을 검증하는 프로젝트인지, 어떤 기술과 경계를 사용하는지를 설명한다.

- [현재 아키텍처와 기술 스택](architecture.md)
- [포트폴리오 목표 제품·백엔드 아키텍처](portfolio-product-architecture.md)
- [포트폴리오 전환 구현 로드맵](implementation-roadmap.md)
- [개발용 PostgreSQL과 자동 테스트 전략](development-database-and-test-strategy.md)
- [권한 요청·승인 구현 결과](access-request-implementation.md)
- [권한 요청 생성 멱등성 구현 결과](access-request-idempotency.md)
- [Spring Security 인증·인가 구현 결과](authentication-and-authorization.md)
- [Transactional Outbox 감사 이벤트 구현 결과](transactional-outbox-implementation.md)
- [Outbox 실패 격리·재시도·Dead Letter 구현 결과](outbox-failure-isolation.md)
- [보안 이벤트 배치 수집·검색 구현 결과](security-event-ingestion-search.md)
- [규칙 기반 탐지와 경보 워크플로 구현 결과](rule-detection-and-alert-workflow.md)
- [백엔드 전체 구현·운영 가이드](backend-completion-guide.md)
- [백엔드 완료 판정과 후속 작업](backend-status-2026-11-02.md)
- [2026-11-02 로컬 실동작 검증](live-smoke-test-2026-11-02.md)
- [2026-11-02 100만 건 벤치마크 및 성능 결과](performance-benchmark-2026-11-02.md)

기능을 추가하거나 인프라를 바꾸면 먼저 이 문서가 실제 코드와 일치하는지 확인한다.
