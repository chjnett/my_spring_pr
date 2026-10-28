package com.secureflow.secureflow.authorization.persistence;

import com.secureflow.secureflow.authorization.domain.Role;
import com.secureflow.secureflow.authorization.domain.RoleGrant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * userId와 role 조합의 DB UNIQUE 제약이 동시 요청의 최종 정합성을 보장하는지 검증한다.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=true",
        "spring.datasource.hikari.maximum-pool-size=24"
})
class RoleGrantConcurrencyPostgresTest {

    private static final int REQUEST_COUNT = 20;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private RoleGrantRepository roleGrantRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(REQUEST_COUNT);

    @AfterEach
    void shutDownExecutor() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("동일 사용자에게 같은 권한 요청 20건이 동시에 들어와도 PostgreSQL에는 한 건만 저장된다")
    void databaseUniqueConstraintKeepsOnlyOneRoleGrantDuringConcurrentRequests() throws Exception {
        Long userId = 100L;
        Role role = Role.ADMIN;
        CountDownLatch checkedRoleGrant = new CountDownLatch(REQUEST_COUNT);
        CountDownLatch startSaving = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();
        for (int requestNumber = 0; requestNumber < REQUEST_COUNT; requestNumber++) {
            futures.add(executor.submit(grantAfterCheckingExistingRole(
                    userId, role, checkedRoleGrant, startSaving
            )));
        }

        assertThat(checkedRoleGrant.await(10, TimeUnit.SECONDS)).isTrue();
        startSaving.countDown();

        int successCount = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(10, TimeUnit.SECONDS)) {
                successCount++;
            }
        }

        long savedRoleGrantCount = roleGrantRepository.countByUserIdAndRole(userId, role);

        System.out.printf(
                "ROLE_GRANT_CONCURRENCY_RESULT total=%d success=%d unique_conflicts=%d persisted=%d%n",
                REQUEST_COUNT,
                successCount,
                REQUEST_COUNT - successCount,
                savedRoleGrantCount
        );

        assertThat(successCount).isEqualTo(1);
        assertThat(savedRoleGrantCount).isEqualTo(1);
        assertThat(REQUEST_COUNT - successCount).isEqualTo(19);
    }

    private Callable<Boolean> grantAfterCheckingExistingRole(
            Long userId,
            Role role,
            CountDownLatch checkedRoleGrant,
            CountDownLatch startSaving
    ) {
        return () -> {
            try {
                return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                    boolean alreadyGranted = roleGrantRepository.existsByUserIdAndRole(userId, role);
                    checkedRoleGrant.countDown();
                    await(startSaving);

                    if (alreadyGranted) {
                        return false;
                    }

                    roleGrantRepository.saveAndFlush(new RoleGrant(userId, role));
                    return true;
                }));
            } catch (RuntimeException exception) {
                return false;
            }
        };
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 권한 부여 테스트 시작 시간이 초과됐습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시 권한 부여 테스트가 중단됐습니다.", exception);
        }
    }
}
