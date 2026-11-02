package com.secureflow.secureflow.authorization.persistence;

import com.secureflow.secureflow.authorization.application.AccessRequestService;
import com.secureflow.secureflow.authorization.application.CreateAccessRequestCommand;
import com.secureflow.secureflow.authorization.application.IdempotentAccessRequestResult;
import com.secureflow.secureflow.authorization.domain.Role;
import com.secureflow.secureflow.authorization.domain.RoleGrantRequestStatus;
import com.secureflow.secureflow.user.User;
import com.secureflow.secureflow.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
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

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.hikari.maximum-pool-size=24"
})
class AccessRequestIdempotencyConcurrencyPostgresTest {

    private static final int REQUEST_COUNT = 20;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired AccessRequestService accessRequestService;
    @Autowired RoleGrantRequestRepository requestRepository;
    @Autowired IdempotencyRecordRepository idempotencyRecordRepository;
    @Autowired UserRepository userRepository;

    private final ExecutorService executor = Executors.newFixedThreadPool(REQUEST_COUNT);

    @AfterEach
    void shutDownExecutor() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("같은 멱등성 키의 권한 요청 20건은 한 건만 생성하고 최초 201 결과를 재사용한다")
    void createsOneAccessRequestForConcurrentRetries() throws Exception {
        User requester = userRepository.saveAndFlush(new User("requester", "requester@example.com"));
        User target = userRepository.saveAndFlush(new User("target", "target@example.com"));
        User reviewer = userRepository.saveAndFlush(new User("reviewer", "reviewer@example.com"));
        CreateAccessRequestCommand command = new CreateAccessRequestCommand(
                target.getId(), Role.AUDITOR, "보안 이벤트 검토"
        );
        String sameKey = "create-auditor-request-once";
        CountDownLatch ready = new CountDownLatch(REQUEST_COUNT);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<IdempotentAccessRequestResult>> futures = new ArrayList<>();
        for (int number = 0; number < REQUEST_COUNT; number++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return accessRequestService.createIdempotently(requester.getId(), command, sameKey);
            }));
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int created = 0;
        int replayed = 0;
        Long requestId = null;
        for (Future<IdempotentAccessRequestResult> future : futures) {
            IdempotentAccessRequestResult result = future.get(15, TimeUnit.SECONDS);
            if (result.replayed()) {
                replayed++;
            } else {
                created++;
            }
            if (requestId == null) {
                requestId = result.accessRequest().id();
            }
            assertThat(result.accessRequest().id()).isEqualTo(requestId);
            assertThat(result.accessRequest().status()).isEqualTo(RoleGrantRequestStatus.PENDING);
        }

        assertThat(created).isEqualTo(1);
        assertThat(replayed).isEqualTo(19);
        assertThat(requestRepository.count()).isEqualTo(1);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(1);

        accessRequestService.approve(requestId, reviewer.getId(), "검토 완료");
        IdempotentAccessRequestResult replayAfterApproval = accessRequestService.createIdempotently(
                requester.getId(), command, sameKey
        );

        System.out.printf(
                "ACCESS_REQUEST_IDEMPOTENCY_RESULT total=%d created=%d replayed=%d persisted=%d records=%d%n",
                REQUEST_COUNT, created, replayed, requestRepository.count(), idempotencyRecordRepository.count()
        );

        assertThat(replayAfterApproval.replayed()).isTrue();
        assertThat(replayAfterApproval.accessRequest().status()).isEqualTo(RoleGrantRequestStatus.PENDING);
        assertThat(replayAfterApproval.accessRequest().version()).isZero();
        assertThat(requestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(RoleGrantRequestStatus.APPROVED);
    }
}
