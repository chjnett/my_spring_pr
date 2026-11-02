# 권한 요청 상태 머신과 낙관적 락 스터디

이 문서는 이번 구현을 읽은 뒤 직접 설명하고 수정해 보기 위한 학습 자료다. 먼저 다음 코드를 함께 읽는다.

- `RoleGrantRequest`: 상태와 상태 전이 규칙
- `AccessRequestService`: 트랜잭션과 flush 순서
- `AccessRequestConcurrencyPostgresTest`: 실제 PostgreSQL 경쟁 재현
- `V2__create_role_grant_requests.sql`: 버전 컬럼과 조회 인덱스

## 1. 상태를 문자열 수정이 아닌 규칙으로 보는 이유

권한 요청의 가능한 전이는 다음 두 개뿐이다.

```text
PENDING → APPROVED
PENDING → REJECTED
```

`APPROVED → REJECTED` 같은 전이를 Service의 단순 setter 조합으로 허용하면 호출 위치마다 규칙이 달라진다. Entity 메서드가 현재 상태와 요청자·검토자를 함께 검사하면 규칙을 한 곳에서 설명할 수 있다.

확인 질문:

1. `setStatus(APPROVED)`를 public으로 두면 어떤 잘못된 상태를 만들 수 있는가?
2. 자기 승인 금지 규칙은 Controller, Service, Entity 중 어디에 있어야 우회하기 어려운가?
3. 취소 상태 `CANCELLED`를 추가한다면 누가 어느 상태에서 취소할 수 있어야 하는가?

## 2. lost update와 `@Version`

두 트랜잭션이 모두 같은 요청을 읽으면 메모리에는 각각 `version=0` 객체가 생긴다. `@Version`이 있으면 Hibernate는 다음 형태로 갱신한다.

```sql
update role_grant_requests
set status = 'APPROVED', version = 1
where id = ? and version = 0;
```

먼저 실행된 트랜잭션만 한 행을 수정한다. 나중 트랜잭션은 수정 행 수가 0이므로 이미 변경된 데이터라는 사실을 감지한다.

비교할 것:

- 낙관적 락: 충돌이 드물다고 보고 동시에 읽게 한 뒤 쓰는 시점에 충돌 감지
- 비관적 락: 읽는 시점부터 DB row lock을 잡아 다른 트랜잭션을 대기시킴
- UNIQUE 제약: 동일 비즈니스 키의 중복 저장 방지. 상태 전이 충돌의 의미까지 설명하지는 못함

## 3. 왜 `saveAndFlush`를 먼저 호출하는가

JPA 변경 감지는 보통 트랜잭션 커밋 시점까지 SQL 실행을 미룬다. 승인 후 곧바로 역할을 저장하면 SQL 순서가 의도와 달라질 수 있다. 이번 코드는 요청 UPDATE를 먼저 flush해 누가 승인권을 획득했는지 확정한 뒤 역할 INSERT를 수행한다.

단, flush는 commit이 아니다. 뒤의 역할 저장이 실패하면 같은 트랜잭션의 요청 UPDATE도 롤백된다.

확인 질문:

1. `save`와 `saveAndFlush`의 차이는 무엇인가?
2. flush 후 예외가 나면 DB 변경이 항상 남는가?
3. `AccessRequestService.approve`에서 `@Transactional`을 제거하면 어떤 원자성이 깨지는가?

## 4. actor 헤더에서 인증 principal로 전환

초기 구현의 `X-Actor-Id`는 공격자가 다른 ID를 넣을 수 있는 임시 입력이었다. 현재는 다음 Spring Security 흐름으로 교체했다.

```text
Authorization 헤더 → Spring Security 인증 → Authentication principal → actorId
```

요청 body에 actor ID를 넣지 않는 이유는 비즈니스 데이터와 인증 주체를 분리하기 위해서다. 현재 Controller는 인증 principal의 user ID만 사용한다.

## 직접 해볼 실습

1. `@Version`을 잠시 제거하고 동시성 테스트가 어떤 오류 또는 결과를 내는지 기록한다.
2. `saveAndFlush`와 역할 저장 순서를 바꾸고 발생하는 예외 종류를 비교한다.
3. `CANCELLED` 상태와 “요청자만 PENDING 요청을 취소” 규칙을 테스트 우선으로 추가한다.
4. 목록 API에 `page`, `size`, `sort`를 추가하고 최대 size를 100으로 제한한다.
5. `MEMBER`와 `AUDITOR` principal로 승인 API의 `403/200` 차이를 테스트한다.

완료 기준은 코드를 외우는 것이 아니라, “왜 DB UNIQUE만으로는 요청 상태 경쟁을 충분히 표현하지 못하는가”와 “flush는 commit이 아니다”를 자신의 말로 설명하는 것이다.
