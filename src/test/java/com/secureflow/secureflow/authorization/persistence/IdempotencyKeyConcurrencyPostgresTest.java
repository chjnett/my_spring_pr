package com.secureflow.secureflow.authorization.persistence;

import com.secureflow.secureflow.authorization.application.GrantRoleCommand;
import com.secureflow.secureflow.authorization.application.IdempotentGrantedRole;
import com.secureflow.secureflow.authorization.application.RoleGrantService;
import com.secureflow.secureflow.authorization.domain.Role;
import com.secureflow.secureflow.user.User;
import com.secureflow.secureflow.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동일 Idempotency-Key의 요청은 한 번만 실제 권한 부여를 하고, 나머지는 그 결과를 재사용해야 한다.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=24"
})
class IdempotencyKeyConcurrencyPostgresTest {

    private static final int REQUEST_COUNT = 20;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private RoleGrantService roleGrantService;

    @Autowired
    private RoleGrantRepository roleGrantRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private UserRepository userRepository;

    private final ExecutorService executor = Executors.newFixedThreadPool(REQUEST_COUNT);

    @AfterEach
    void shutDownExecutor() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("동일 Idempotency-Key 20건은 권한을 한 번만 부여하고 19건은 최초 201 결과를 재사용한다")
    void replaysTheOriginalResultForConcurrentRequestsWithTheSameKey() throws Exception {
        User user = userRepository.saveAndFlush(new User("idempotency-test", "idempotency@example.com"));
        String sameKey = "grant-admin-once";
        CountDownLatch ready = new CountDownLatch(REQUEST_COUNT);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<IdempotentGrantedRole>> futures = new ArrayList<>();
        for (int requestNumber = 0; requestNumber < REQUEST_COUNT; requestNumber++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return roleGrantService.grantRoleIdempotently(
                        user.getId(), new GrantRoleCommand(Role.ADMIN), sameKey
                );
            }));
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int firstResponses = 0;
        int replayedResponses = 0;
        Long grantId = null;
        for (Future<IdempotentGrantedRole> future : futures) {
            IdempotentGrantedRole result = future.get(15, TimeUnit.SECONDS);
            if (result.replayed()) {
                replayedResponses++;
            } else {
                firstResponses++;
            }
            if (grantId == null) {
                grantId = result.grantedRole().id();
            }
            assertThat(result.grantedRole().id()).isEqualTo(grantId);
            assertThat(result.grantedRole().role()).isEqualTo(Role.ADMIN);
        }

        long roleGrantCount = roleGrantRepository.countByUserIdAndRole(user.getId(), Role.ADMIN);
        long idempotencyRecordCount = idempotencyRecordRepository.count();

        System.out.printf(
                "IDEMPOTENCY_CONCURRENCY_RESULT total=%d first_201=%d replayed_201=%d role_grants=%d idempotency_records=%d%n",
                REQUEST_COUNT, firstResponses, replayedResponses, roleGrantCount, idempotencyRecordCount
        );

        assertThat(firstResponses).isEqualTo(1);
        assertThat(replayedResponses).isEqualTo(19);
        assertThat(roleGrantCount).isEqualTo(1);
        assertThat(idempotencyRecordCount).isEqualTo(1);
    }
}
