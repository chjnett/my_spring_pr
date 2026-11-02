package com.secureflow.secureflow.detection;

import com.secureflow.secureflow.detection.application.AlertService;
import com.secureflow.secureflow.detection.application.DetectionRunResult;
import com.secureflow.secureflow.detection.application.DetectionService;
import com.secureflow.secureflow.detection.application.DetectionWatermarkService;
import com.secureflow.secureflow.detection.domain.AlertStatus;
import com.secureflow.secureflow.detection.domain.DetectionRule;
import com.secureflow.secureflow.detection.domain.SecurityAlert;
import com.secureflow.secureflow.detection.persistence.SecurityAlertRepository;
import com.secureflow.secureflow.security.BootstrapAdminService;
import com.secureflow.secureflow.user.User;
import com.secureflow.secureflow.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "secureflow.detection.window-size=10m",
        "secureflow.detection.ingestion-grace=30s"
})
class RuleDetectionPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired DetectionService detectionService;
    @Autowired DetectionWatermarkService watermarkService;
    @Autowired SecurityAlertRepository alertRepository;
    @Autowired AlertService alertService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired UserRepository userRepository;
    @Autowired BootstrapAdminService bootstrapAdminService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mockMvc;
    @Autowired MeterRegistry meterRegistry;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("delete from security_alerts");
        jdbcTemplate.update("delete from detection_watermarks");
        jdbcTemplate.update("delete from security_events");
    }

    @Test
    void detectsThresholdBoundaryAndDeduplicatesRerun() {
        Instant from = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        Instant to = from.plusSeconds(600);
        for (int index = 0; index < 4; index++) {
            insertEvent("LOGIN_FAILED", 11L, "10.0.0.11", "FAILURE", from.plusSeconds(index + 1));
        }
        insertEvent("LOGIN_FAILED", 11L, "10.0.0.11", "FAILURE", to);

        DetectionRunResult belowThreshold = detectionService.detectWindow(from, to);
        assertThat(belowThreshold.candidates()).isZero();

        insertEvent("LOGIN_FAILED", 11L, "10.0.0.11", "FAILURE", to.minusNanos(1_000));
        DetectionRunResult threshold = detectionService.detectWindow(from, to);
        DetectionRunResult rerun = detectionService.detectWindow(from, to);

        assertThat(threshold.created()).isEqualTo(1);
        assertThat(rerun.created()).isZero();
        assertThat(rerun.duplicates()).isEqualTo(1);
        SecurityAlert alert = alertRepository.findAll().getFirst();
        assertThat(alert.getRuleCode()).isEqualTo(DetectionRule.ACTOR_LOGIN_FAILURE_BURST);
        assertThat(alert.getEventCount()).isEqualTo(5);
        assertThat(alert.getStatus()).isEqualTo(AlertStatus.OPEN);
    }

    @Test
    void detectsAllFourMvpRules() {
        Instant from = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        Instant to = from.plusSeconds(600);
        for (int index = 0; index < 5; index++) {
            insertEvent("LOGIN_FAILED", 100L, "10.0.0.100", "FAILURE", from.plusSeconds(index));
            insertEvent("ACCESS_DENIED", 200L, "10.0.0.200", "DENIED", from.plusSeconds(index));
        }
        for (int index = 0; index < 10; index++) {
            insertEvent("LOGIN_FAILED", 1000L + index, "203.0.113.50", "FAILURE", from.plusSeconds(20 + index));
        }
        for (int index = 0; index < 3; index++) {
            insertEvent("PRIVILEGED_ACTION", 300L, "10.0.0.300", "FAILURE", from.plusSeconds(40 + index));
        }

        DetectionRunResult result = detectionService.detectWindow(from, to);

        assertThat(result.created()).isEqualTo(4);
        assertThat(alertRepository.findAll()).extracting(SecurityAlert::getRuleCode)
                .containsExactlyInAnyOrder(DetectionRule.values());
        assertThat(meterRegistry.get("secureflow.detection.event.to.alert.latency").timer().count())
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    void advancesWatermarkOnlyAcrossProcessedRange() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        for (int index = 0; index < 5; index++) {
            insertEvent("ACCESS_DENIED", 77L, "198.51.100.77", "DENIED", now.minusSeconds(120 - index));
        }

        DetectionRunResult first = watermarkService.runOnce(now);
        DetectionRunResult second = watermarkService.runOnce(now.plusSeconds(300));
        Instant saved = jdbcTemplate.queryForObject(
                "select watermark from detection_watermarks where pipeline = 'MVP_RULES'",
                (resultSet, rowNumber) -> resultSet.getObject(1, OffsetDateTime.class).toInstant()
        );

        assertThat(first.created()).isEqualTo(1);
        assertThat(saved).isEqualTo(now.minusSeconds(30));
        assertThat(second.candidates()).isZero();
    }

    @Test
    void alertApiRequiresRoleAndSupportsAssignThenResolve() throws Exception {
        Instant from = Instant.now().minusSeconds(300).truncatedTo(ChronoUnit.MICROS);
        for (int index = 0; index < 5; index++) {
            insertEvent("ACCESS_DENIED", 88L, "192.0.2.88", "DENIED", from.plusSeconds(index));
        }
        detectionService.detectWindow(from, from.plusSeconds(60));
        Long alertId = alertRepository.findAll().getFirst().getId();
        var admin = bootstrapAdminService.ensureAdmin(
                "phase4-admin", "phase4-admin@example.com", "phase4-admin-password"
        );

        mockMvc.perform(get("/api/alerts")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/alerts/{id}/resolve", alertId)
                        .with(httpBasic("phase4-admin@example.com", "phase4-admin-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionNote\":\"담당자 없는 즉시 해결\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ALERT_TRANSITION"));
        mockMvc.perform(post("/api/alerts/{id}/assign", alertId)
                        .with(httpBasic("phase4-admin@example.com", "phase4-admin-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":" + admin.id() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVESTIGATING"))
                .andExpect(jsonPath("$.assigneeId").value(admin.id()));
        mockMvc.perform(post("/api/alerts/{id}/resolve", alertId)
                        .with(httpBasic("phase4-admin@example.com", "phase4-admin-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionNote\":\"사용자 확인 후 계정 보호 완료\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
        assertThat(meterRegistry.get("secureflow.alert.workflow")
                .tag("action", "assigned").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(meterRegistry.get("secureflow.alert.workflow")
                .tag("action", "resolved").counter().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void optimisticLockAllowsOnlyOneConcurrentAlertUpdate() throws Exception {
        Instant from = Instant.now().minusSeconds(300).truncatedTo(ChronoUnit.MICROS);
        for (int index = 0; index < 5; index++) {
            insertEvent("ACCESS_DENIED", 99L, "192.0.2.99", "DENIED", from.plusSeconds(index));
        }
        detectionService.detectWindow(from, from.plusSeconds(60));
        Long alertId = alertRepository.findAll().getFirst().getId();
        User firstAnalyst = userRepository.save(new User("analyst-1", "analyst-1@example.com"));
        User secondAnalyst = userRepository.save(new User("analyst-2", "analyst-2@example.com"));
        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch update = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            for (User analyst : List.of(firstAnalyst, secondAnalyst)) {
                executor.submit(() -> {
                    try {
                        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                            SecurityAlert alert = alertRepository.findById(alertId).orElseThrow();
                            loaded.countDown();
                            await(update);
                            alert.assign(analyst.getId(), analyst.getId(), Instant.now());
                            alertRepository.saveAndFlush(alert);
                        });
                    } catch (Throwable throwable) {
                        synchronized (failures) {
                            failures.add(throwable);
                        }
                    }
                });
            }
            loaded.await();
            update.countDown();
        }

        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst()).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(alertRepository.findById(alertId).orElseThrow().getVersion()).isEqualTo(1L);
    }

    private void insertEvent(
            String eventType, Long actorId, String sourceIp, String outcome, Instant occurredAt
    ) {
        String eventId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into security_events
                    (event_id, aggregate_type, aggregate_id, event_type, actor_id, payload,
                     occurred_at, ingested_at, source, source_ip, outcome)
                values (?, 'ExternalSecurityEvent', ?, ?, ?, '{}', ?, ?, 'test-source', ?, ?)
                """, eventId, eventId, eventType, actorId, Timestamp.from(occurredAt),
                Timestamp.from(occurredAt.plusSeconds(1)), sourceIp, outcome);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
