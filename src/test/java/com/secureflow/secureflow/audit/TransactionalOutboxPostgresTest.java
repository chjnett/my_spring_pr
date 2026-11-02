package com.secureflow.secureflow.audit;

import com.secureflow.secureflow.audit.application.AuditOutboxPublisher;
import com.secureflow.secureflow.audit.application.AuditIntegrityVerifier;
import com.secureflow.secureflow.audit.application.AuditEventSigner;
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
import com.secureflow.secureflow.global.observability.CorrelationIdContext;
import com.secureflow.secureflow.user.User;
import com.secureflow.secureflow.user.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
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
        "secureflow.outbox.publisher.max-attempts=2",
        "secureflow.outbox.publisher.base-backoff=0ms",
        "secureflow.outbox.publisher.max-backoff=0ms",
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
    @Autowired MeterRegistry meterRegistry;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("delete from security_events");
        jdbcTemplate.update("delete from audit_outbox_dead_letter");
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
    void isolatesPoisonEventAndContinuesThenMovesItToDeadLetter() {
        double publishedBefore = counter("secureflow.outbox.published");
        double failuresBefore = counter("secureflow.outbox.failures");
        double retriesBefore = counter("secureflow.outbox.retries");
        double deadLettersBefore = counter("secureflow.outbox.dead.letters");
        AuditOutboxEvent poison = new AuditOutboxEvent(
                "RoleGrantRequest", "poison", AuditEventType.ACCESS_REQUEST_CREATED,
                1L, "{\"poison\":true}"
        );
        AuditOutboxEvent healthy = new AuditOutboxEvent(
                "RoleGrantRequest", "healthy", AuditEventType.ACCESS_REQUEST_CREATED,
                1L, "{\"healthy\":true}"
        );
        outboxRepository.saveAllAndFlush(List.of(poison, healthy));
        installPoisonEventTrigger();

        assertThat(publisher.publishBatch(3)).isEqualTo(1);

        assertThat(outboxRepository.findById(poison.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.FAILED);
        assertThat(outboxRepository.findById(poison.getId()).orElseThrow().getAttemptCount())
                .isEqualTo(2);
        assertThat(outboxRepository.findById(healthy.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_outbox_dead_letter where event_id = ?",
                Integer.class, poison.getEventId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from security_events where event_id = ?",
                Integer.class, healthy.getEventId()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from security_events where event_id = ?",
                Integer.class, poison.getEventId()
        )).isZero();
        assertThat(counter("secureflow.outbox.published") - publishedBefore).isEqualTo(1);
        assertThat(counter("secureflow.outbox.failures") - failuresBefore).isEqualTo(2);
        assertThat(counter("secureflow.outbox.retries") - retriesBefore).isEqualTo(1);
        assertThat(counter("secureflow.outbox.dead.letters") - deadLettersBefore).isEqualTo(1);
        assertThat(meterRegistry.get("secureflow.outbox.pending").gauge().value()).isZero();
        assertThat(meterRegistry.get("secureflow.outbox.dead.letter.count").gauge().value()).isEqualTo(1);
    }

    @Test
    void detectsCorrelationAndPayloadTamperingAfterEventWasPublished() {
        TestUsers users = users();
        MDC.put(CorrelationIdContext.MDC_KEY, "integration-request-123");
        try {
            accessRequestService.createIdempotently(
                    users.requesterId(),
                    new CreateAccessRequestCommand(users.targetId(), Role.AUDITOR, "무결성 검증"),
                    "integrity-create"
            );
        } finally {
            MDC.remove(CorrelationIdContext.MDC_KEY);
        }
        assertThat(publisher.publishBatch(100)).isEqualTo(1);

        String eventId = jdbcTemplate.queryForObject(
                "select event_id from security_events", String.class
        );
        assertThat(integrityVerifier.verifySecurityEvent(eventId))
                .isEqualTo(AuditIntegrityStatus.VALID);
        String correlationId = jdbcTemplate.queryForObject(
                "select correlation_id from security_events where event_id = ?",
                String.class, eventId
        );
        Integer integrityVersion = jdbcTemplate.queryForObject(
                "select integrity_version from security_events where event_id = ?",
                Integer.class, eventId
        );
        assertThat(correlationId).isEqualTo("integration-request-123");
        assertThat(integrityVersion).isEqualTo(AuditEventSigner.CORRELATION_VERSION);

        jdbcTemplate.update(
                "update security_events set correlation_id = ? where event_id = ?",
                "tampered-request", eventId
        );
        assertThat(integrityVerifier.verifySecurityEvent(eventId))
                .isEqualTo(AuditIntegrityStatus.INVALID);

        jdbcTemplate.update(
                "update security_events set correlation_id = ? where event_id = ?",
                "integration-request-123", eventId
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

    private void installPoisonEventTrigger() {
        jdbcTemplate.execute("""
                create or replace function reject_poison_security_event()
                returns trigger as $$
                begin
                    if new.aggregate_id = 'poison' then
                        raise exception 'simulated downstream rejection';
                    end if;
                    return new;
                end;
                $$ language plpgsql
                """);
        jdbcTemplate.execute("drop trigger if exists reject_poison_event on security_events");
        jdbcTemplate.execute("""
                create trigger reject_poison_event
                before insert on security_events
                for each row execute function reject_poison_security_event()
                """);
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
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
