package com.secureflow.secureflow.user;

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
 * existsByEmail() 다음 save()를 수행하는 흐름이 동시에 실행될 때의 경쟁 조건을 재현한다.
 * UNIQUE 제약이 최종 정합성을 지키는지 PostgreSQL에서 확인한다.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=true",
        "spring.datasource.hikari.maximum-pool-size=24"
})
class DuplicateEmailConcurrencyPostgresTest {

    private static final int REQUEST_COUNT = 20;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(REQUEST_COUNT);

    @AfterEach
    void shutDownExecutor() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("동일 이메일 가입 20건이 동시에 들어와도 PostgreSQL에는 한 건만 저장된다")
    void databaseUniqueConstraintKeepsOnlyOneUserDuringConcurrentRegistration() throws Exception {
        String email = "race@example.com";
        CountDownLatch checkedEmail = new CountDownLatch(REQUEST_COUNT);
        CountDownLatch startSaving = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();
        for (int requestNumber = 0; requestNumber < REQUEST_COUNT; requestNumber++) {
            futures.add(executor.submit(registerAfterCheckingEmail(email, checkedEmail, startSaving)));
        }

        assertThat(checkedEmail.await(10, TimeUnit.SECONDS)).isTrue();
        startSaving.countDown();

        int successCount = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(10, TimeUnit.SECONDS)) {
                successCount++;
            }
        }

        long savedUserCount = userRepository.countByEmail(email);

        System.out.printf(
                "CONCURRENCY_RESULT total=%d success=%d unique_conflicts=%d persisted=%d%n",
                REQUEST_COUNT,
                successCount,
                REQUEST_COUNT - successCount,
                savedUserCount
        );

        assertThat(successCount).isEqualTo(1);
        assertThat(savedUserCount).isEqualTo(1);
        assertThat(REQUEST_COUNT - successCount).isEqualTo(19);
    }

    private Callable<Boolean> registerAfterCheckingEmail(
            String email,
            CountDownLatch checkedEmail,
            CountDownLatch startSaving
    ) {
        return () -> {
            try {
                return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                    boolean alreadyExists = userRepository.existsByEmail(email);
                    checkedEmail.countDown();
                    await(startSaving);

                    if (alreadyExists) {
                        return false;
                    }

                    userRepository.saveAndFlush(new User("race", email));
                    return true;
                }));
            } catch (RuntimeException exception) {
                // PostgreSQL UNIQUE 제약 충돌은 실패 요청으로 집계한다.
                return false;
            }
        };
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 시작 시간이 초과됐습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트가 중단됐습니다.", exception);
        }
    }
}
