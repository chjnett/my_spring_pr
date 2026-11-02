package com.secureflow.secureflow.audit;

import com.secureflow.secureflow.audit.application.AuditIntegrityVerifier;
import com.secureflow.secureflow.audit.application.AuditEventSigner;
import com.secureflow.secureflow.audit.domain.AuditIntegrityStatus;
import com.secureflow.secureflow.audit.ingest.SecurityEventBatchRequest;
import com.secureflow.secureflow.audit.ingest.SecurityEventBatchResponse;
import com.secureflow.secureflow.audit.ingest.SecurityEventIngestService;
import com.secureflow.secureflow.audit.ingest.SecurityEventIngestStatus;
import com.secureflow.secureflow.audit.ingest.SecurityEventItemRequest;
import com.secureflow.secureflow.audit.ingest.ServiceCredentialRegistry;
import com.secureflow.secureflow.audit.query.SecurityEventPage;
import com.secureflow.secureflow.audit.query.SecurityEventQueryException;
import com.secureflow.secureflow.audit.query.SecurityEventQueryService;
import com.secureflow.secureflow.security.BootstrapAdminService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        "secureflow.security-events.ingest.enabled=true",
        "secureflow.security-events.ingest.source-id=identity-service",
        "secureflow.security-events.ingest.api-key=test-service-credential-at-least-32-characters"
})
class SecurityEventIngestionPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("db/init/create-secureflow-app-role.sql");

    @Autowired SecurityEventIngestService ingestService;
    @Autowired SecurityEventQueryService queryService;
    @Autowired AuditIntegrityVerifier integrityVerifier;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;
    @Autowired BootstrapAdminService bootstrapAdminService;
    @Autowired MeterRegistry meterRegistry;
    @Autowired ServiceCredentialRegistry serviceCredentialRegistry;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("delete from security_events");
    }

    @Test
    void acceptsValidEventsReportsDuplicateAndRejectsInvalidItem() {
        Instant occurredAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        String eventId = UUID.randomUUID().toString();
        String injectionText = "x'); drop table security_events; --";

        SecurityEventBatchResponse response = ingestService.ingest(new SecurityEventBatchRequest(List.of(
                event(eventId, "LOGIN_FAILED", 11L, "192.168.001.010", "FAILURE", occurredAt,
                        Map.of("reasonCode", injectionText)),
                event(eventId, "LOGIN_FAILED", 11L, "192.168.1.10", "FAILURE", occurredAt,
                        Map.of()),
                event(UUID.randomUUID().toString(), "LOGIN_FAILED", 11L, "192.168.1.11", "FAILURE",
                        occurredAt, Map.of("password", "must-not-be-stored"))
        )));

        assertThat(response.accepted()).isEqualTo(1);
        assertThat(response.duplicate()).isEqualTo(1);
        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.results()).extracting(result -> result.status())
                .containsExactly(
                        SecurityEventIngestStatus.ACCEPTED,
                        SecurityEventIngestStatus.DUPLICATE,
                        SecurityEventIngestStatus.REJECTED
                );
        assertThat(response.results().get(2).errorCode()).isEqualTo("ATTRIBUTE_NOT_ALLOWED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from security_events", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select payload from security_events where event_id = ?", String.class, eventId
        )).contains(injectionText);
        assertThat(integrityVerifier.verifySecurityEvent(eventId)).isEqualTo(AuditIntegrityStatus.VALID);
        assertThat(jdbcTemplate.queryForObject(
                "select integrity_version from security_events where event_id = ?", Integer.class, eventId
        )).isEqualTo(AuditEventSigner.SEARCH_FIELDS_VERSION);
        jdbcTemplate.update("update security_events set source_ip = ? where event_id = ?", "192.168.1.99", eventId);
        assertThat(integrityVerifier.verifySecurityEvent(eventId)).isEqualTo(AuditIntegrityStatus.INVALID);
        jdbcTemplate.update("update security_events set source_ip = ? where event_id = ?", "192.168.1.10", eventId);
        assertThat(integrityVerifier.verifySecurityEvent(eventId)).isEqualTo(AuditIntegrityStatus.VALID);
        assertThat(meterRegistry.get("secureflow.security.events.ingest")
                .tag("result", "accepted").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(meterRegistry.get("secureflow.security.events.ingest")
                .tag("result", "duplicate").counter().count()).isGreaterThanOrEqualTo(1);
        assertThat(meterRegistry.get("secureflow.security.events.ingest")
                .tag("result", "rejected").counter().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void applicationRoleCanAppendButCannotMutateOrDeleteSecurityEvents() {
        assertThat(tablePrivilege("SELECT")).isTrue();
        assertThat(tablePrivilege("INSERT")).isTrue();
        assertThat(tablePrivilege("UPDATE")).isFalse();
        assertThat(tablePrivilege("DELETE")).isFalse();
    }

    @Test
    void requiresServiceCredentialForIngestEndpoint() throws Exception {
        String body = """
                {
                  "events": [{
                    "eventId": "%s",
                    "eventType": "LOGIN_FAILED",
                    "actorId": 55,
                    "sourceIp": "203.0.113.10",
                    "outcome": "FAILURE",
                    "occurredAt": "%s",
                    "attributes": {"reasonCode": "BAD_PASSWORD"}
                  }]
                }
                """.formatted(UUID.randomUUID(), Instant.now().minusSeconds(5));

        mockMvc.perform(post("/api/internal/security-events:batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SERVICE_CREDENTIAL"));

        mockMvc.perform(post("/api/internal/security-events:batch")
                        .header("X-SecureFlow-Ingest-Key", "test-service-credential-at-least-32-characters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(0));
    }

    @Test
    void usesCredentialRegistrySourceAndRejectsRevokedCredential() throws Exception {
        String credentialId = "edge-gateway-2026";
        String secret = "edge-gateway-secret-at-least-32-characters";
        serviceCredentialRegistry.register(credentialId, "edge-gateway", secret, Instant.now().plusSeconds(3600));
        String body = """
                {"events":[{"eventId":"%s","eventType":"ACCESS_DENIED","actorId":9,
                "sourceIp":"203.0.113.7","outcome":"DENIED","occurredAt":"%s",
                "attributes":{"resource":"/admin"}}]}
                """.formatted(UUID.randomUUID(), Instant.now().minusSeconds(5));

        mockMvc.perform(post("/api/internal/security-events:batch")
                        .header("X-SecureFlow-Credential-Id", credentialId)
                        .header("X-SecureFlow-Ingest-Key", secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());
        assertThat(jdbcTemplate.queryForObject(
                "select source from security_events order by id desc limit 1", String.class
        )).isEqualTo("edge-gateway");

        assertThat(serviceCredentialRegistry.revoke(credentialId)).isTrue();
        mockMvc.perform(post("/api/internal/security-events:batch")
                        .header("X-SecureFlow-Credential-Id", credentialId)
                        .header("X-SecureFlow-Ingest-Key", secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SERVICE_CREDENTIAL"));
    }

    @Test
    void searchesWithFiltersAndStableCursorPagination() {
        Instant base = Instant.now().minusSeconds(300).truncatedTo(ChronoUnit.MICROS);
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        String third = UUID.randomUUID().toString();
        ingestService.ingest(new SecurityEventBatchRequest(List.of(
                event(first, "LOGIN_FAILED", 22L, "10.0.0.1", "FAILURE", base.plusSeconds(1), Map.of()),
                event(second, "LOGIN_FAILED", 22L, "10.0.0.1", "FAILURE", base.plusSeconds(2), Map.of()),
                event(third, "ACCESS_DENIED", 33L, "10.0.0.2", "DENIED", base.plusSeconds(3), Map.of())
        )));

        SecurityEventPage firstPage = queryService.search(
                base, base.plusSeconds(10), null, null, null, null, 2, null
        );
        SecurityEventPage secondPage = queryService.search(
                base, base.plusSeconds(10), null, null, null, null, 2, firstPage.nextCursor()
        );
        SecurityEventPage filtered = queryService.search(
                base, base.plusSeconds(10), "LOGIN_FAILED", 22L, "10.0.0.1", "FAILURE", 50, null
        );

        assertThat(firstPage.events()).extracting(event -> event.eventId())
                .containsExactly(third, second);
        assertThat(firstPage.nextCursor()).isNotBlank();
        assertThat(secondPage.events()).extracting(event -> event.eventId()).containsExactly(first);
        assertThat(secondPage.nextCursor()).isNull();
        assertThat(filtered.events()).extracting(event -> event.eventId())
                .containsExactly(second, first);
        assertThat(meterRegistry.get("secureflow.security.events.search.latency").timer().count())
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void protectsSearchAndReturnsStableApiContract() throws Exception {
        Instant occurredAt = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        String eventId = UUID.randomUUID().toString();
        ingestService.ingest(new SecurityEventBatchRequest(List.of(
                event(eventId, "ACCESS_DENIED", 77L, "198.51.100.7", "DENIED", occurredAt,
                        Map.of("resource", "/admin"))
        )));
        bootstrapAdminService.ensureAdmin(
                "phase3-admin", "phase3-admin@example.com", "phase3-admin-password"
        );

        mockMvc.perform(get("/api/security-events"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/security-events")
                        .with(httpBasic("phase3-admin@example.com", "phase3-admin-password"))
                        .param("eventType", "ACCESS_DENIED")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.events[0].eventId").value(eventId))
                .andExpect(jsonPath("$.events[0].sourceIp").value("198.51.100.7"));

        mockMvc.perform(get("/api/security-events")
                        .with(httpBasic("phase3-admin@example.com", "phase3-admin-password"))
                        .param("eventType", "ACCESS_DENIED' OR 1=1 --"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SECURITY_EVENT_QUERY"));
    }

    @Test
    void rejectsUnsafeQueryAndUsesSourceIpIndex() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> queryService.search(
                now.minus(32, ChronoUnit.DAYS), now, null, null, null, null, 50, null
        )).isInstanceOf(SecurityEventQueryException.class)
                .hasMessageContaining("31일");
        assertThatThrownBy(() -> queryService.search(
                now.minusSeconds(60), now, "LOGIN_FAILED' OR 1=1 --", null, null, null, 50, null
        )).isInstanceOf(SecurityEventQueryException.class)
                .hasMessageContaining("eventType");
        assertThatThrownBy(() -> queryService.search(
                now.minusSeconds(60), now, null, null, null, null, 201, null
        )).isInstanceOf(SecurityEventQueryException.class)
                .hasMessageContaining("200");

        jdbcTemplate.execute("set enable_seqscan = off");
        List<String> plan = jdbcTemplate.queryForList("""
                explain select id from security_events
                where source_ip = '10.0.0.1'
                order by occurred_at desc, id desc
                limit 50
                """, String.class);
        assertThat(String.join(" ", plan)).contains("idx_security_events_source_ip_occurred");
    }

    private SecurityEventItemRequest event(
            String eventId,
            String eventType,
            Long actorId,
            String sourceIp,
            String outcome,
            Instant occurredAt,
            Map<String, String> attributes
    ) {
        return new SecurityEventItemRequest(
                eventId, eventType, actorId, sourceIp, outcome, occurredAt, attributes
        );
    }

    private boolean tablePrivilege(String privilege) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select has_table_privilege('secureflow_app', 'security_events', ?)",
                Boolean.class,
                privilege
        ));
    }
}
