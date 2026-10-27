# User 수정 API — 구현 리뷰와 다음 학습 과제

## 현재 목표

`PATCH /api/users/{id}`로 사용자의 이름만 수정한다. 이 기능을 통해 Controller 검증, Service 트랜잭션, Entity 상태 변경, JPA 변경 감지를 한 흐름으로 이해한다.

## 잘한 점

- `UpdateUserRequest`를 따로 만들고 `name`만 받도록 제한했다. 수정 API가 이메일 변경까지 무심코 허용하지 않게 된다.
- Controller에서 `@Valid @RequestBody`를 사용하려 했다. HTTP 요청 검증 책임을 Controller 경계에 둔 방향이 맞다.
- `UserServiceTest`에서 `@Mock`과 `@BeforeEach`를 사용해 Repository 가짜 객체와 Service를 분리했다.
- 수정 로직에 `@Transactional`을 붙이려 했다. 변경 감지를 관찰하기 위한 필수 조건이다.

## 최종 테스트 결과

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

## 적용한 답안

1. `User.changeName(String name)`으로 Entity의 상태 변경 책임을 한정했다.
2. Controller는 `@PatchMapping("/{id}")`, `@PathVariable`, `@Valid @RequestBody`로 HTTP 입력을 받는다.
3. Service는 `modifyUser(Long id, UpdateUserRequest request)`에서 사용자를 조회하고 `changeName()`만 호출한다.
4. Service는 `save()`를 호출하지 않는다. 트랜잭션 안에서 관리되는 Entity의 변경을 JPA가 감지하도록 둔다.
5. `UserServiceTest`는 `src/test/java`에만 두고, 수정 성공·없는 사용자·`save()` 미호출을 테스트했다.

## 변경 감지 딥다이브 — 컴파일 통과 후 진행

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
