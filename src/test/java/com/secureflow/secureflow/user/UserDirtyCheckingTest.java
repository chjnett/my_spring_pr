package com.secureflow.secureflow.user;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class UserDirtyCheckingTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void updatesManagedEntityWithoutCallingSave() {
        // given: User를 저장하고 flush한다.(1차 캐시를 디비에 반영)
        //save->jpa가 관리하는 놈

        User user=userRepository.save(new User("before","dirty-checking@example.com"));
        entityManager.flush();

        // when: 저장한 User의 이름만 바꾸고 flush한다.
        user.changeName("after");
        entityManager.flush();
        // then: clear 후 다시 조회해서 DB의 값이 바뀌었는지 확인한다.
        entityManager.clear();
        User foundUser = userRepository.findById(user.getId()).orElseThrow();

        assertThat(foundUser.getName()).isEqualTo("after");
    }
    @Test
    void doesNotUpdateDetachedEntityWithoutMerge() {
        // given
        User user = userRepository.save(
                new User("before", "detached@example.com")
        );
        Long userId = user.getId();

        entityManager.flush();
        entityManager.clear(); // 여기서 user는 detached 상태가 된다.

        // when
        user.changeName("detached");

        entityManager.flush(); // 관리 중인 변경 Entity가 없으므로 UPDATE가 없어야 한다.
        entityManager.clear();

        // then
        User foundUser = userRepository.findById(userId).orElseThrow();

        assertThat(foundUser.getName()).isEqualTo("before");
    }
}
