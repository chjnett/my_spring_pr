package com.secureflow.secureflow.audit;

import com.secureflow.secureflow.audit.application.AuditOutboxPublisher;
import com.secureflow.secureflow.audit.application.AuditIntegrityVerifier;
import com.secureflow.secureflow.audit.domain.AuditIntegrityStatus;
import com.secureflow.secureflow.audit.domain.AuditEventType;
import com.secureflow.secureflow.audit.domain.AuditOutboxEvent;
import com.secureflow.secureflow.audit.domain.OutboxStatus;
import com.secureflow.secureflow.audit.persistence.AuditOutboxRepository;
import com.secureflow.secureflow.authorization.application.AccessRequestService;
import com.secureflow.secureflow.authorization.application.CreateAccessRequestCommand;
import com.secureflow.secureflow.authorization.domain.Role;
import com.secureflow.secureflow.authorization.domain.RoleGrant;
import com.secureflow.secureflow.authorization.domain.RoleGrantRequestStatus;
import com.secureflow.secureflow.authorization.persistence.RoleGrantRepository;
import com.secureflow.secureflow.authorization.persistence.RoleGrantRequestRepository;
import com.secureflow.secureflow.global.exception.DuplicateRoleGrantException;
import com.secureflow.secureflow.user.User;
import com.secureflow.secureflow.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "secureflow.outbox.publisher.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=8"
})
class TransactionalOutboxPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired AccessRequestService accessRequestService;
    @Autowired AuditOutboxPublisher publisher;
    @Autowired AuditIntegrityVerifier integrityVerifier;
    @Autowired AuditOutboxRepository outboxRepository;
    @Autowired RoleGrantRequestRepository requestRepository;
    @Autowired RoleGrantRepository roleGrantRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("delete from security_events");
        outboxRepository.deleteAllInBatch();
        jdbcTemplate.update("delete from idempotency_records");
        requestRepository.deleteAllInBatch();
        roleGrantRepository.deleteAllInBatch();
        jdbcTemplate.update("delete from user_credentials");
        userRepository.deleteAllInBatch();
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void commitsBusinessChangeAndOutboxThenPublishesExactlyOnce() {
        TestUsers users = users();

        Long requestId = accessRequestService.createIdempotently(
                users.requesterId(),
                new CreateAccessRequestCommand(users.targetId(), Role.AUDITOR, "보안 이벤트 검토"),
                "outbox-create"
        ).accessRequest().id();
        accessRequestService.approve(requestId, users.reviewerId(), "업무 확인");

        assertThat(outboxRepository.countByEventType(AuditEventType.ACCESS_REQUEST_CREATED)).isEqualTo(1);
        assertThat(outboxRepository.countByEventType(AuditEventType.ACCESS_REQUEST_APPROVED)).isEqualTo(1);
        assertThat(outboxRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(2);

        assertThat(publisher.publishBatch(100)).isEqualTo(2);
        assertThat(publisher.publishBatch(100)).isZero();

        Integer securityEventCount = jdbcTemplate.queryForObject(
                "select count(*) from security_events", Integer.class
        );
        Integer distinctEventCount = jdbcTemplate.queryForObject(
                "select count(distinct event_id) from security_events", Integer.class
        );

        assertThat(securityEventCount).isEqualTo(2);
        assertThat(distinctEventCount).isEqualTo(2);
        assertThat(outboxRepository.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(2);
    }

    @Test
    void rollsBackApprovalOutboxWhenRoleGrantFails() {
        TestUsers users = users();
        roleGrantRepository.saveAndFlush(new RoleGrant(users.targetId(), Role.MEMBER));
        Long requestId = accessRequestService.createIdempotently(
                users.requesterId(),
                new CreateAccessRequestCommand(users.targetId(), Role.MEMBER, "이미 보유한 역할"),
                "rollback-create"
        ).accessRequest().id();

        assertThatThrownBy(() -> accessRequestService.approve(
                requestId, users.reviewerId(), "승인 시도"
        )).isInstanceOf(DuplicateRoleGrantException.class);

        assertThat(requestRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo(RoleGrantRequestStatus.PENDING);
        assertThat(outboxRepository.countByEventType(AuditEventType.ACCESS_REQUEST_APPROVED)).isZero();
        assertThat(outboxRepository.countByEventType(AuditEventType.ACCESS_REQUEST_CREATED)).isEqualTo(1);
    }

    @Test
    void concurrentPublishersUseSkipLockedAndPersistEachEventOnce() throws Exception {
        List<AuditOutboxEvent> events = new ArrayList<>();
        for (int number = 0; number < 20; number++) {
            events.add(new AuditOutboxEvent(
                    "RoleGrantRequest", String.valueOf(number), AuditEventType.ACCESS_REQUEST_CREATED,
                    1L, "{\"sequence\":" + number + "}"
            ));
        }
        outboxRepository.saveAllAndFlush(events);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Integer>> futures = List.of(
                executor.submit(() -> publishAfterSignal(ready, start)),
                executor.submit(() -> publishAfterSignal(ready, start))
        );
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int published = futures.get(0).get(10, TimeUnit.SECONDS)
                + futures.get(1).get(10, TimeUnit.SECONDS);
        Integer securityEventCount = jdbcTemplate.queryForObject(
                "select count(*) from security_events", Integer.class
        );
        Integer duplicateCount = jdbcTemplate.queryForObject("""
                select count(*) from (
                    select event_id from security_events group by event_id having count(*) > 1
                ) duplicates
                """, Integer.class);

        System.out.printf(
                "OUTBOX_CONCURRENCY_RESULT pending=20 claimed=%d events=%d duplicates=%d%n",
                published, securityEventCount, duplicateCount
        );

        assertThat(published).isEqualTo(20);
        assertThat(securityEventCount).isEqualTo(20);
        assertThat(duplicateCount).isZero();
        assertThat(outboxRepository.countByStatus(OutboxStatus.PUBLISHED)).isEqualTo(20);
    }

    @Test
    void detectsPayloadTamperingAfterEventWasPublished() {
        TestUsers users = users();
        accessRequestService.createIdempotently(
                users.requesterId(),
                new CreateAccessRequestCommand(users.targetId(), Role.AUDITOR, "무결성 검증"),
                "integrity-create"
        );
        assertThat(publisher.publishBatch(100)).isEqualTo(1);

        String eventId = jdbcTemplate.queryForObject(
                "select event_id from security_events", String.class
        );
        assertThat(integrityVerifier.verifySecurityEvent(eventId))
                .isEqualTo(AuditIntegrityStatus.VALID);

        jdbcTemplate.update(
                "update security_events set payload = ? where event_id = ?",
                "{\"tampered\":true}", eventId
        );

        assertThat(integrityVerifier.verifySecurityEvent(eventId))
                .isEqualTo(AuditIntegrityStatus.INVALID);
    }

    private int publishAfterSignal(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return publisher.publishBatch(20);
    }

    private TestUsers users() {
        User requester = userRepository.saveAndFlush(new User("requester", "outbox-requester@example.com"));
        User target = userRepository.saveAndFlush(new User("target", "outbox-target@example.com"));
        User reviewer = userRepository.saveAndFlush(new User("reviewer", "outbox-reviewer@example.com"));
        return new TestUsers(requester.getId(), target.getId(), reviewer.getId());
    }

    private record TestUsers(Long requesterId, Long targetId, Long reviewerId) {
    }
}
