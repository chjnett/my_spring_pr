# 사용자 수정 API와 JPA 변경 감지

## 한 줄 결론

사용자 이름 수정은 Controller에서 입력을 검증하고, Service 트랜잭션 안에서 managed Entity의 상태만 바꾸며, Hibernate 변경 감지가 flush 시점에 UPDATE를 실행하도록 설계했다.

## P — Problem

사용자 수정 API에서 Entity를 직접 반환하거나, 모든 변경에 습관적으로 `save()`를 호출하면 HTTP 경계와 영속성 컨텍스트의 책임이 섞인다. 수정이 실제 DB에 언제 반영되는지 설명할 수 없으면 트랜잭션 경계를 안전하게 설계하기 어렵다.

## A — Analyze

| 선택지 | 장점 | 한계 | 선택 |
| --- | --- | --- | --- |
| Controller에서 Entity를 직접 수정 | 코드가 짧아 보임 | HTTP와 도메인 책임이 섞임 | 미선택 |
| Service에서 `save()`를 매번 호출 | 눈에 보이는 저장 호출 | managed Entity의 변경 감지 의도를 흐림 | 미선택 |
| Service 트랜잭션 안에서 `changeName()` 호출 | 상태 변경 책임과 flush 시점을 분리 | managed 상태 이해가 필요함 | 선택 |

## A — Action

### 구현 목표

`PATCH /api/users/{id}`로 사용자의 이름만 수정한다. 이 기능을 통해 Controller 검증, Service 트랜잭션, Entity 상태 변경, JPA 변경 감지를 한 흐름으로 이해한다.

### 적용한 설계

- `UpdateUserRequest`를 따로 만들고 `name`만 받도록 제한했다. 수정 API가 이메일 변경까지 무심코 허용하지 않게 된다.
- Controller에서 `@Valid @RequestBody`를 사용하려 했다. HTTP 요청 검증 책임을 Controller 경계에 둔 방향이 맞다.
- `UserServiceTest`에서 `@Mock`과 `@BeforeEach`를 사용해 Repository 가짜 객체와 Service를 분리했다.
- 수정 로직에 `@Transactional`을 붙이려 했다. 변경 감지를 관찰하기 위한 필수 조건이다.

### 컴파일 오류와 수정

처음에는 아래 Java 문법 오류로 `./gradlew test`가 컴파일 단계에서 실패했다.

```text
UserService.java:71: error: illegal start of expression
modifyUser(Long id, UpdateUserRequest request@Valid UpdateUserRequest request)
```

`@Valid`는 HTTP 요청을 받는 Controller 파라미터에 두고, Service 메서드는 검증이 끝난 DTO를 받도록 분리한다. 따라서 Service 시그니처에는 `UpdateUserRequest request`가 한 번만 있어야 한다.

수정 후 초기 Gradle daemon이 test worker를 찾지 못하는 도구 오류가 한 번 발생했다. daemon을 종료하고 다음 명령으로 재실행해 해결했다.

```bash
./gradlew --stop
./gradlew clean test --no-daemon
```

결과: **BUILD SUCCESSFUL**

### 구현 답안

1. `User.changeName(String name)`으로 Entity의 상태 변경 책임을 한정했다.
2. Controller는 `@PatchMapping("/{id}")`, `@PathVariable`, `@Valid @RequestBody`로 HTTP 입력을 받는다.
3. Service는 `modifyUser(Long id, UpdateUserRequest request)`에서 사용자를 조회하고 `changeName()`만 호출한다.
4. Service는 `save()`를 호출하지 않는다. 트랜잭션 안에서 관리되는 Entity의 변경을 JPA가 감지하도록 둔다.
5. `UserServiceTest`는 `src/test/java`에만 두고, 수정 성공·없는 사용자·`save()` 미호출을 테스트했다.

## R — Result

- 수정 성공·없는 사용자·불필요한 `save()` 미호출을 단위 테스트로 확인했다.
- `./gradlew clean test --no-daemon`이 통과했다.
- 별도 [JPA 변경 감지 딥다이브](../deep-dives/jpa-dirty-checking.md)에서 managed와 detached의 SQL 차이를 추가 검증한다.

## 다음 실험

수정 API에서 `user.changeName(request.name())` 후 `save()`를 호출하지 않는다. 다음 실습에서는 PostgreSQL SQL 로그를 관찰해 아래를 직접 확인한다.

1. `findById()`로 읽은 Entity가 영속성 컨텍스트에서 관리되는 시점
2. `changeName()` 호출 직후가 아니라 트랜잭션 종료 전 flush 시점에 `UPDATE`가 나가는 이유
3. `@Transactional`을 제거했을 때 왜 같은 코드가 안전하게 업데이트되지 않는지
4. `save()`를 추가했을 때와 추가하지 않았을 때 SQL 및 의도의 차이

## 완료 기준

- [x] `PATCH /api/users/{id}`가 수정된 DTO를 반환하도록 구현
- [x] 빈 이름은 Controller의 `@Valid` 검증 대상으로 설정
- [x] 없는 사용자는 `UserNotFoundException` → `404 USER_NOT_FOUND`
- [x] Service 수정 코드에 불필요한 `save()`가 없음
- [x] 수정 성공과 실패 단위 테스트 작성
- [x] `./gradlew clean test --no-daemon` 통과
- [ ] 변경 감지를 자신의 말로 설명
