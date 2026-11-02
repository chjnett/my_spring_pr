package com.secureflow.secureflow.authorization.persistence;

import com.secureflow.secureflow.authorization.domain.Role;
import com.secureflow.secureflow.authorization.domain.RoleGrant;
import com.secureflow.secureflow.authorization.domain.RoleGrantRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.hikari.maximum-pool-size=8"
})
class AccessRequestConcurrencyPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired RoleGrantRequestRepository requestRepository;
    @Autowired RoleGrantRepository roleGrantRepository;
    @Autowired TransactionTemplate transactionTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutDownExecutor() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("두 검토자가 같은 요청을 동시에 승인해도 낙관적 락으로 한 건만 역할을 부여한다")
    void optimisticLockAllowsOnlyOneApproval() throws Exception {
        RoleGrantRequest saved = requestRepository.saveAndFlush(
                new RoleGrantRequest(1L, 2L, Role.ADMIN, "운영 배포")
        );
        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch approve = new CountDownLatch(1);

        List<Future<Boolean>> futures = List.of(
                executor.submit(() -> approve(saved.getId(), 3L, loaded, approve)),
                executor.submit(() -> approve(saved.getId(), 4L, loaded, approve))
        );

        assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
        approve.countDown();

        int successCount = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(10, TimeUnit.SECONDS)) {
                successCount++;
            }
        }

        long grantedCount = roleGrantRepository.countByUserIdAndRole(2L, Role.ADMIN);
        RoleGrantRequest decided = requestRepository.findById(saved.getId()).orElseThrow();

        System.out.printf(
                "ACCESS_REQUEST_CONCURRENCY_RESULT approvals=2 success=%d conflicts=%d role_grants=%d version=%d%n",
                successCount, 2 - successCount, grantedCount, decided.getVersion()
        );

        assertThat(successCount).isEqualTo(1);
        assertThat(grantedCount).isEqualTo(1);
        assertThat(decided.getVersion()).isEqualTo(1L);
    }

    private boolean approve(
            Long requestId,
            Long reviewerId,
            CountDownLatch loaded,
            CountDownLatch startApproval
    ) {
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                RoleGrantRequest request = requestRepository.findById(requestId).orElseThrow();
                loaded.countDown();
                await(startApproval);

                request.approve(reviewerId, "동시 승인 테스트");
                requestRepository.saveAndFlush(request);
                roleGrantRepository.saveAndFlush(new RoleGrant(request.getTargetUserId(), request.getRequestedRole()));
                return true;
            }));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 승인 테스트 시작 시간이 초과됐습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시 승인 테스트가 중단됐습니다.", exception);
        }
    }
}
