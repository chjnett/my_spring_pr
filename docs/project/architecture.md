# SecureFlow 현재 구현 아키텍처와 기술 스택

## 프로젝트 목적

이 문서는 현재 구현된 기준선을 설명한다. 최종 제품 방향과 JPA/JDBC 경계는 [포트폴리오 목표 제품·백엔드 아키텍처](portfolio-product-architecture.md)를 따른다. 현재 단계의 목표는 CRUD 개수를 늘리는 것이 아니라, 트랜잭션과 데이터베이스 제약이 실제로 어떤 실패를 막는지 테스트로 증명하는 것이다.

## 현재 구조

```text
HTTP Client
    ↓
Spring Security              HTTP Basic 인증, DB principal, 역할 기반 인가
    ↓
UserController              요청 검증, HTTP 상태 코드와 DTO 경계
    ↓
UserService (@Transactional) 유스케이스, 중복 오류를 도메인 오류로 번역
    ↓
UserRepository               JPA 조회·저장
    ↓
PostgreSQL                   users.email UNIQUE로 최종 정합성 보장

RoleGrantController          권한 부여 HTTP 계약
    ↓
RoleGrantService             권한 부여·Idempotency-Key 유스케이스와 트랜잭션
    ↓
IdempotencyRecordRepository  같은 네트워크 작업의 원자적 선점·응답 재사용
    ↓
UserExistencePort            user 모듈에 요구하는 최소 조회 계약
    ↓
UserJpaExistenceAdapter      현재는 UserRepository로 계약을 구현
    ↓
RoleGrantRepository → PostgreSQL role_grants(user_id, role) UNIQUE

AccessRequestController       권한 요청·승인·거절 HTTP 계약
    ↓
AccessRequestService          상태 전이와 역할 부여의 단일 트랜잭션
    ↓
RoleGrantRequest (@Version)   PENDING → APPROVED/REJECTED, 동시 수정 감지
    ↓
RoleGrantRequestRepository → PostgreSQL role_grant_requests
```

## 기술 스택

| 영역 | 현재 선택 | 역할 |
| --- | --- | --- |
| Language | Java 21 | 애플리케이션 언어 |
| Framework | Spring Boot 4.1 | Web MVC, Validation, JPA 실행 환경 |
| Security | Spring Security, BCrypt | DB 인증 principal과 역할 기반 접근 제어 |
| Persistence | Spring Data JPA / Hibernate | Entity 관리와 변경 감지 |
| Development DB | PostgreSQL 16 Docker Compose | IntelliJ에서 API를 직접 확인하는 영속 환경 |
| Fast test | JUnit 5, Mockito, H2 | 빠른 단위·JPA 테스트 |
| Integration test | Testcontainers PostgreSQL 16 | 실제 PostgreSQL 제약·동시성 검증 |
| Build | Gradle | 컴파일과 테스트 실행 |

## 현재 사용자 API 계약

| API | 성공 | 실패 예시 |
| --- | --- | --- |
| `POST /api/users` | `201 Created` | 잘못된 입력 `400`, 중복 이메일 `409 DUPLICATE_EMAIL` |
| `PATCH /api/users/{id}` | `200 OK` | 없는 사용자 `404 USER_NOT_FOUND`, 잘못된 이름 `400` |
| `POST /api/users/{userId}/role-grants` | `201 Created` | `Idempotency-Key` 재사용 본문 불일치 `422`, 새 키의 같은 권한 `409` |
| `POST /api/access-requests` | `201 Created` | 없는 사용자 `404`, 키 재사용 본문 불일치 `422` |
| `POST /api/access-requests/{id}/approve` | `200 OK` | 자기 승인 `403`, 재처리·동시 수정 `409` |
| `POST /api/access-requests/{id}/reject` | `200 OK` | 자기 검토 `403`, 재처리·동시 수정 `409` |

## 설계 원칙

1. Controller는 HTTP 입력 검증과 응답 계약을 담당한다.
2. Service는 유스케이스와 트랜잭션 경계를 담당한다.
3. Entity는 자신의 상태 변경 메서드로 불변 조건을 드러낸다.
4. 사전 조회는 사용자 친화적 오류를 위한 보조 수단이고, 최종 중복 방지는 DB 제약이 맡는다.
5. 테스트는 개발용 Compose DB를 공유하지 않는다.
6. 모듈 경계를 넘을 때는 다른 모듈의 Entity가 아니라 ID와 Port 계약을 사용한다.
7. 멱등성 키는 `UNIQUE(user_id, request_scope, idempotency_key)`로 먼저 선점하고, 권한 변경과 최초 응답 기록을 하나의 트랜잭션으로 완료한다.
8. 검토가 필요한 상태 전이는 `@Version`으로 경쟁을 감지하고, 요청 UPDATE를 flush한 뒤 역할을 저장한다.
9. 권한 요청 생성은 API scope별 멱등성 키를 원자적으로 선점하고 최초 `PENDING` 응답을 재생한다.
10. actor ID는 클라이언트 입력이 아니라 인증 principal에서 가져오고, 검토 API는 `ADMIN/AUDITOR`에만 허용한다.

## 다음 확장 경로

권한 요청 상태·멱등성·인증·인가 흐름을 완성했다. 다음 단계에서는 직접 역할 부여 API를 내부 관리 경로로 정리하고 Transactional Outbox 감사 로그로 확장한다.
