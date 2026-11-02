# Spring Security principal과 RBAC 스터디

## 1. 인증과 인가를 구분하기

- 인증(Authentication): 이 요청을 보낸 사용자가 누구인지 확인한다.
- 인가(Authorization): 확인된 사용자가 이 작업을 수행할 수 있는지 판단한다.

이번 구현에서 이메일·비밀번호 검증은 인증이고, `MEMBER`가 승인 API를 호출하지 못하게 하는 것은 인가다.

확인 질문:

1. 비밀번호가 맞아도 왜 승인 API에서 `403`을 받을 수 있는가?
2. 인증되지 않은 `401`과 권한이 부족한 `403`은 어떻게 다른가?
3. Controller가 body의 `userId`를 actor로 사용하면 인증을 해도 어떤 취약점이 남는가?

## 2. principal이 만들어지는 과정

```text
Basic credentials
  → UserDetailsService.loadUserByUsername(email)
  → users에서 사용자 조회
  → user_credentials에서 BCrypt hash 조회
  → role_grants를 ROLE_* authority로 변환
  → SecureFlowPrincipal
```

Controller는 `Principal` 안의 `SecureFlowPrincipal.userId`만 actor로 사용한다. 클라이언트는 actor ID를 직접 선택하지 못한다.

## 3. 비밀번호를 별도 테이블에 둔 이유

사용자 API 응답용 프로필과 인증 자격 증명의 접근 경계를 나눈다. `UserResponse`를 수정하거나 로그로 남겨도 password hash가 실수로 포함될 가능성을 줄인다. 비밀번호 원문은 어떤 테이블에도 저장하지 않는다.

BCrypt는 같은 비밀번호도 salt 때문에 서로 다른 hash를 만들 수 있다. 따라서 문자열 hash를 직접 비교하지 않고 `PasswordEncoder.matches(raw, encoded)`를 사용한다.

## 4. 역할 기반 접근 제어

현재 역할은 다음과 같이 사용한다.

- `MEMBER`: 권한 요청 생성
- `AUDITOR`: 요청 조회·승인·거절
- `ADMIN`: 사용자 관리, 직접 역할 부여, 요청 검토

역할은 “사용자 종류”라기보다 허용된 작업 묶음이다. 기능이 늘어나면 `ACCESS_REQUEST_REVIEW` 같은 세부 permission으로 분해하는 방식을 검토한다.

## 5. CSRF와 stateless

CSRF 방어는 브라우저가 자동 첨부하는 cookie 기반 인증에서 특히 중요하다. 현재 HTTP Basic API는 세션 cookie를 만들지 않고 `STATELESS`로 동작하므로 CSRF를 비활성화했다. 나중에 browser cookie나 세션 인증을 도입한다면 그대로 두면 안 된다.

## 직접 해볼 실습

1. 미인증, 잘못된 비밀번호, 권한 부족 요청의 `401/403` 차이를 확인한다.
2. DB의 BCrypt hash가 같은 비밀번호로 가입한 두 사용자 사이에서도 다른지 비교한다.
3. `MEMBER`에게 `AUDITOR`를 부여한 전후 승인 API 결과를 비교한다.
4. bootstrap 환경 변수 중 email만 설정하고 애플리케이션 시작 실패를 확인한다.
5. HTTP Basic을 JWT로 교체한다면 폐기·만료·role 변경 반영을 어떻게 처리할지 설계한다.

완료 기준은 Security Filter, `UserDetailsService`, principal, authority가 각각 어떤 책임을 가지는지 요청 흐름 순서대로 설명하는 것이다.
