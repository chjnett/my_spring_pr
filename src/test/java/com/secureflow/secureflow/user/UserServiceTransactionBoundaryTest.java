package com.secureflow.secureflow.user;

import com.secureflow.secureflow.user.dto.CreateUserRequest;
import com.secureflow.secureflow.user.dto.UpdateUserRequest;
import com.secureflow.secureflow.user.dto.UserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Service 메서드의 @Transactional은 Spring Bean 프록시를 통해 호출될 때 적용된다.
 * 이 테스트는 프록시 호출과 직접 new로 생성한 호출을 비교한다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:transaction-boundary;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=true"
})
class UserServiceTransactionBoundaryTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void updatesUserWhenSpringProxyAppliesTransactional() {
        UserResponse createdUser = userService.createUser(
                new CreateUserRequest("before", "proxy@example.com")
        );

        userService.modifyUser(createdUser.id(), new UpdateUserRequest("after"));

        UserResponse foundUser = userService.getUser(createdUser.id());

        assertThat(foundUser.name()).isEqualTo("after");
    }

    @Test
    void doesNotUpdateUserWhenServiceIsCreatedWithoutSpringProxy() {
        UserResponse createdUser = userService.createUser(
                new CreateUserRequest("before", "without-proxy@example.com")
        );
        UserService serviceWithoutProxy = new UserService(userRepository);

        serviceWithoutProxy.modifyUser(createdUser.id(), new UpdateUserRequest("after"));

        UserResponse foundUser = userService.getUser(createdUser.id());

        assertThat(foundUser.name()).isEqualTo("before");
    }
}
