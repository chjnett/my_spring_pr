# Spring Security 인증·인가 구현 결과

기준일: 2026-11-02

## 변경 목표

이전 API는 클라이언트가 보낸 `X-Actor-Id`를 그대로 신뢰했다. 사용자는 임의의 ID를 넣어 다른 요청자나 검토자인 것처럼 행동할 수 있었다. 현재는 DB 자격 증명으로 인증된 Spring Security principal에서만 actor ID를 읽는다.

```text
Authorization: Basic email:password
              ↓
Spring Security HTTP Basic filter
              ↓
SecureFlowUserDetailsService
  users + user_credentials + role_grants 조회
              ↓
SecureFlowPrincipal(userId, email, authorities)
              ↓
Controller → AccessRequestService
```

## 저장 모델

Flyway V4가 `user_credentials`를 추가한다.

| 데이터 | 저장 위치 | 설명 |
| --- | --- | --- |
| 이름·이메일 | `users` | 사용자 프로필과 이메일 UNIQUE |
| 비밀번호 | `user_credentials.password_hash` | BCrypt hash만 저장, 원문 저장 금지 |
| 권한 | `role_grants` | `MEMBER`, `AUDITOR`, `ADMIN` |

회원 생성은 사용자, 자격 증명, 기본 `MEMBER` 역할을 하나의 트랜잭션으로 저장한다. 중간 저장이 실패하면 전부 롤백된다. API 응답에는 password와 hash를 포함하지 않는다.

## 접근 정책

| 경로 | 정책 |
| --- | --- |
| `POST /api/users` | 공개 회원 가입, password 12~72자 |
| `POST /api/access-requests` | 인증 사용자 |
| 권한 요청 조회 | `ADMIN` 또는 `AUDITOR` |
| 권한 요청 승인·거절 | `ADMIN` 또는 `AUDITOR` |
| 직접 역할 부여 | `ADMIN` |
| 사용자 조회·수정·삭제 | `ADMIN` |
| health/info/prometheus | 현재 개발 환경에서 공개 |

HTTP 세션은 만들지 않고 `STATELESS`로 설정했다. CSRF는 브라우저 cookie 세션을 사용하지 않는 API이므로 비활성화했다.

## 최초 관리자 bootstrap

모든 가입자가 `MEMBER`로 시작하고 역할 부여는 `ADMIN`만 할 수 있으므로 최초 관리자 경로가 필요하다. 다음 환경 변수를 처음 한 번 설정하고 애플리케이션을 실행한다.

```bash
SECUREFLOW_BOOTSTRAP_ADMIN_EMAIL=admin@example.com \
SECUREFLOW_BOOTSTRAP_ADMIN_PASSWORD='change-this-password' \
./gradlew bootRun
```

- 이메일이 없으면 사용자·자격 증명·`MEMBER`·`ADMIN`을 생성한다.
- 이메일이 이미 있으면 제공된 비밀번호와 기존 BCrypt hash가 일치할 때만 누락된 `ADMIN`을 추가한다. 비밀번호는 덮어쓰지 않는다.
- email 또는 password 중 하나만 설정되면 시작을 실패시킨다.
- 원문 비밀번호를 로그에 출력하지 않는다.
- 초기화 후 배포 환경에서 bootstrap password 변수를 제거해야 한다.
- 여러 인스턴스의 최초 동시 bootstrap은 아직 별도 분산 락이 없으므로 배포 작업 한 곳에서 먼저 실행한다.

## 실제 통합 검증

PostgreSQL 16 Testcontainers와 실제 Spring Security filter chain으로 다음을 확인했다.

- 비밀번호 원문과 BCrypt hash가 다름
- `PasswordEncoder.matches` 성공
- 미인증 요청 `401`
- 잘못된 비밀번호 `401`
- `MEMBER`의 권한 요청 생성 `201`
- `MEMBER`의 승인 시도 `403`
- bootstrap `ADMIN`의 승인 `200`
- 승인 응답의 reviewer ID가 인증 principal의 user ID와 동일
- Flyway V1~V7과 Hibernate schema validation 통과

2026-11-02 최신 전체 회귀 결과는 `./gradlew test --rerun-tasks` 기준 **51개 테스트 통과, 실패 0개**다.

## 현재 선택의 한계

HTTP Basic은 구현 경계와 RBAC를 검증하기 위한 현재 단계의 방식이다. 반드시 TLS가 필요하고 매 요청마다 자격 증명을 전달한다. 외부 공개 제품으로 확장할 때는 OIDC/OAuth2 Authorization Server 또는 짧은 수명의 access token, refresh token 회전, 로그인 rate limit, 계정 잠금, 비밀번호 재설정과 감사 이벤트가 필요하다.
