package com.secureflow.secureflow.audit.ingest;

import com.secureflow.secureflow.audit.application.AuditEventSigner;
import com.secureflow.secureflow.global.observability.CorrelationIdContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class SecurityEventIngestService {

    private final JdbcTemplate jdbcTemplate;
    private final SecurityEventNormalizer normalizer;
    private final SecurityEventIngestProperties properties;
    private final AuditEventSigner signer;
    private final SecurityEventIngestMetrics metrics;

    public SecurityEventIngestService(
            JdbcTemplate jdbcTemplate,
            SecurityEventNormalizer normalizer,
            SecurityEventIngestProperties properties,
            AuditEventSigner signer,
            SecurityEventIngestMetrics metrics
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.normalizer = normalizer;
        this.properties = properties;
        this.signer = signer;
        this.metrics = metrics;
    }

    @Transactional
    public SecurityEventBatchResponse ingest(SecurityEventBatchRequest request) {
        return ingest(request, properties.getSourceId());
    }

    @Transactional
    public SecurityEventBatchResponse ingest(SecurityEventBatchRequest request, String sourceId) {
        if (request.events().size() > properties.getMaxBatchSize()) {
            throw new IllegalArgumentException("한 배치는 최대 " + properties.getMaxBatchSize() + "건입니다.");
        }

        Instant now = Instant.now();
        String correlationId = CorrelationIdContext.currentOrCreate();
        SecurityEventItemResult[] results = new SecurityEventItemResult[request.events().size()];
        List<PreparedEvent> prepared = new ArrayList<>();

        for (int index = 0; index < request.events().size(); index++) {
            SecurityEventNormalizer.NormalizationResult normalized = normalizer.normalize(
                    request.events().get(index), now
            );
            if (!normalized.accepted()) {
                results[index] = SecurityEventItemResult.rejected(
                        index, normalized.eventId(), normalized.errorCode(), normalized.message()
                );
                continue;
            }
            SecurityEventNormalizer.NormalizedSecurityEvent event = normalized.event();
            AuditEventSigner.SignedAuditEvent signature = signer.signWithCorrelation(
                    event.eventId(), "ExternalSecurityEvent", event.eventId(), event.eventType(),
                    event.actorId(), event.payload(), event.occurredAt(), correlationId
            );
            prepared.add(new PreparedEvent(index, event, signature));
        }

        if (!prepared.isEmpty()) {
            List<Object[]> batchArguments = prepared.stream().map(item -> new Object[]{
                    item.event().eventId(),
                    "ExternalSecurityEvent",
                    item.event().eventId(),
                    item.event().eventType(),
                    item.event().actorId(),
                    item.event().payload(),
                    Timestamp.from(item.event().occurredAt()),
                    Timestamp.from(now),
                    item.signature().keyId(),
                    item.signature().signature(),
                    correlationId,
                    item.signature().version(),
                    sourceId,
                    item.event().sourceIp(),
                    item.event().outcome()
            }).toList();
            int[] updateCounts = jdbcTemplate.batchUpdate("""
                    insert into security_events
                        (event_id, aggregate_type, aggregate_id, event_type, actor_id, payload,
                         occurred_at, ingested_at, integrity_key_id, integrity_signature,
                         correlation_id, integrity_version, source, source_ip, outcome)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (event_id) do nothing
                    """, batchArguments);
            for (int index = 0; index < prepared.size(); index++) {
                PreparedEvent item = prepared.get(index);
                boolean inserted = updateCounts[index] > 0
                        || updateCounts[index] == Statement.SUCCESS_NO_INFO;
                results[item.index()] = inserted
                        ? SecurityEventItemResult.accepted(item.index(), item.event().eventId())
                        : SecurityEventItemResult.duplicate(item.index(), item.event().eventId());
            }
        }

        List<SecurityEventItemResult> ordered = Arrays.asList(results);
        int accepted = (int) ordered.stream()
                .filter(result -> result.status() == SecurityEventIngestStatus.ACCEPTED).count();
        int duplicate = (int) ordered.stream()
                .filter(result -> result.status() == SecurityEventIngestStatus.DUPLICATE).count();
        int rejected = ordered.size() - accepted - duplicate;
        SecurityEventBatchResponse response = new SecurityEventBatchResponse(
                accepted, duplicate, rejected, List.copyOf(ordered)
        );
        metrics.record(response);
        return response;
    }

    private record PreparedEvent(
            int index,
            SecurityEventNormalizer.NormalizedSecurityEvent event,
            AuditEventSigner.SignedAuditEvent signature
    ) {
    }
}
