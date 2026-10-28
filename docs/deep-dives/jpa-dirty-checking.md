# 02. JPA 변경 감지 딥다이브

## 질문

`user.changeName("after")`만 호출했는데, 왜 `userRepository.save(user)` 없이 데이터베이스의 `users.name`이 바뀌는가?

## 먼저 세울 가설

`@Transactional` 안에서 Repository가 읽어 온 `User`는 영속성 컨텍스트가 관리한다. 트랜잭션이 끝나기 전 flush 시점에 Hibernate가 처음 읽어 둔 상태와 현재 상태를 비교하고, 다르면 `UPDATE` SQL을 만든다.

이 문장은 아직 답이 아니다. 아래 실험으로 증명해야 한다.

## 실험 A — 관리 상태 Entity 변경

`src/test/java/.../user/UserDirtyCheckingTest.java`를 만들고 `@DataJpaTest`를 사용한다.

테스트가 해야 할 일:

1. User를 저장한다.
2. `flush()`로 INSERT를 DB에 반영한다.
3. `changeName("after")`만 호출한다. 이때 `save()`는 호출하지 않는다.
4. `flush()`를 호출한다.
5. `clear()`로 1차 캐시를 비운다.
6. Repository로 다시 조회해 이름이 `after`인지 검증한다.

뼈대는 아래와 같다.

```java
@DataJpaTest
class UserDirtyCheckingTest {
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;

    @Test
    void updatesManagedEntityWithoutCallingSave() {
        // given: user 저장 후 flush
        // when: user.changeName("after") 호출, save는 호출하지 않음
        // then: flush + clear 후 다시 조회해 "after" 검증
    }
}
```

## 실험 B — 트랜잭션 경계 밖의 Entity

같은 실험을 별도 트랜잭션 경계로 나누어 본다. 핵심 질문은 “변경한 객체가 여전히 managed 상태인가, detached 상태인가?”다. detached Entity의 변경이 자동 반영되지 않는 이유를 설명한다.

## 관찰할 SQL

`application.yml`의 `show-sql: true`를 켠 상태에서 다음을 확인한다.

- `changeName()` 직후에는 UPDATE가 바로 나가지 않을 수 있다.
- `flush()` 또는 트랜잭션 종료 직전에 UPDATE가 나온다.
- `clear()` 뒤 재조회하면 1차 캐시가 아니라 DB의 실제 값을 읽는다.

## 완료 기준

- [ ] 실험 A 테스트를 작성하고 통과
- [ ] `save()`가 없어도 UPDATE가 생긴 SQL 로그 확보
- [ ] `flush()`와 `clear()`의 역할을 각각 설명
- [ ] managed와 detached의 차이를 한 문단으로 설명
- [ ] 결과를 트러블슈팅/학습 기록으로 남김
