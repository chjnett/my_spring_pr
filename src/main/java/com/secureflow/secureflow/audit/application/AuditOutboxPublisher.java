package com.secureflow.secureflow.audit.application;

import com.secureflow.secureflow.audit.observability.OutboxMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class AuditOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(AuditOutboxPublisher.class);
    private static final int MAX_ERROR_LENGTH = 1_000;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OutboxPublisherProperties properties;
    private final OutboxMetrics metrics;

    public AuditOutboxPublisher(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            OutboxPublisherProperties properties,
            OutboxMetrics metrics
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 이벤트마다 독립 트랜잭션을 사용한다. 한 poison event가 실패해도 롤백 범위는 해당 이벤트 하나이며,
     * 다음 이벤트는 계속 발행한다. 실패 이벤트는 지수 backoff 후 재시도하고 한도를 넘으면 DLQ로 격리한다.
     */
    public int publishBatch(int batchSize) {
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("batchSize는 1 이상 1000 이하여야 합니다.");
        }

        int publishedCount = 0;
        for (int processed = 0; processed < batchSize; processed++) {
            try {
                Long publishedId = transactionTemplate.execute(status -> publishNextDueEvent());
                if (publishedId == null) {
                    break;
                }
                publishedCount++;
                metrics.recordPublished();
            } catch (OutboxPublicationException exception) {
                FailureResult result = transactionTemplate.execute(
                        status -> recordFailure(exception.outboxId(), rootMessage(exception))
                );
                if (result == null || result == FailureResult.IGNORED) {
                    log.warn("Outbox 발행 실패 기록을 건너뛰었습니다. outboxId={}", exception.outboxId());
                    continue;
                }
                boolean deadLettered = result == FailureResult.DEAD_LETTERED;
                metrics.recordFailure(deadLettered);
                log.warn(
                        "Outbox 발행에 실패했습니다. outboxId={}, result={}, cause={}",
                        exception.outboxId(), result, rootMessage(exception)
                );
            }
        }
        metrics.refresh();
        return publishedCount;
    }

    private Long publishNextDueEvent() {
        List<PendingEvent> events = jdbcTemplate.query("""
                select id, event_id, aggregate_type, aggregate_id, event_type,
                       actor_id, payload, created_at, integrity_key_id, integrity_signature,
                       correlation_id, integrity_version
                from audit_outbox
                where status = 'PENDING'
                  and (next_attempt_at is null or next_attempt_at <= current_timestamp)
                order by id
                limit 1
                for update skip locked
                """, this::mapPendingEvent);
        if (events.isEmpty()) {
            return null;
        }

        PendingEvent event = events.getFirst();
        try {
            OffsetDateTime publishedAt = OffsetDateTime.now(ZoneOffset.UTC);
            jdbcTemplate.update("""
                    insert into security_events
                        (event_id, aggregate_type, aggregate_id, event_type, actor_id,
                         payload, occurred_at, ingested_at, integrity_key_id, integrity_signature,
                         correlation_id, integrity_version)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (event_id) do nothing
                    """,
                    event.eventId(), event.aggregateType(), event.aggregateId(), event.eventType(),
                    event.actorId(), event.payload(), event.createdAt(), publishedAt,
                    event.integrityKeyId(), event.integritySignature(), event.correlationId(),
                    event.integrityVersion()
            );
            jdbcTemplate.update("""
                    update audit_outbox
                    set status = 'PUBLISHED', published_at = ?, last_error = null,
                        next_attempt_at = null
                    where id = ?
                    """, publishedAt, event.id());
            return event.id();
        } catch (RuntimeException exception) {
            throw new OutboxPublicationException(event.id(), exception);
        }
    }

    private FailureResult recordFailure(Long outboxId, String error) {
        List<Integer> attempts = jdbcTemplate.query(
                "select attempt_count from audit_outbox where id = ? and status = 'PENDING' for update",
                (resultSet, rowNumber) -> resultSet.getInt("attempt_count"),
                outboxId
        );
        if (attempts.isEmpty()) {
            return FailureResult.IGNORED;
        }

        int failedAttemptCount = attempts.getFirst() + 1;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (failedAttemptCount >= properties.getMaxAttempts()) {
            jdbcTemplate.update("""
                    update audit_outbox
                    set status = 'FAILED', attempt_count = ?, last_error = ?,
                        failed_at = ?, next_attempt_at = null
                    where id = ? and status = 'PENDING'
                    """, failedAttemptCount, error, now, outboxId);
            jdbcTemplate.update("""
                    insert into audit_outbox_dead_letter
                        (outbox_id, event_id, aggregate_type, aggregate_id, event_type,
                         actor_id, payload, attempt_count, last_error, failed_at, created_at)
                    select id, event_id, aggregate_type, aggregate_id, event_type,
                           actor_id, payload, attempt_count, last_error, failed_at, created_at
                    from audit_outbox
                    where id = ? and status = 'FAILED'
                    on conflict (event_id) do update
                    set attempt_count = excluded.attempt_count,
                        last_error = excluded.last_error,
                        failed_at = excluded.failed_at
                    """, outboxId);
            return FailureResult.DEAD_LETTERED;
        }

        OffsetDateTime nextAttemptAt = now.plus(properties.retryDelay(failedAttemptCount));
        jdbcTemplate.update("""
                update audit_outbox
                set attempt_count = ?, last_error = ?, next_attempt_at = ?
                where id = ? and status = 'PENDING'
                """, failedAttemptCount, error, nextAttemptAt, outboxId);
        return FailureResult.RETRY_SCHEDULED;
    }

    private String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        if (root instanceof SQLException sqlException) {
            String sqlState = sqlException.getSQLState();
            return root.getClass().getSimpleName()
                    + (sqlState == null ? "" : "[SQLState=" + sqlState + "]");
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            message = root.getClass().getSimpleName();
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_ERROR_LENGTH
                ? normalized
                : normalized.substring(0, MAX_ERROR_LENGTH);
    }

    private PendingEvent mapPendingEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PendingEvent(
                resultSet.getLong("id"),
                resultSet.getString("event_id"),
                resultSet.getString("aggregate_type"),
                resultSet.getString("aggregate_id"),
                resultSet.getString("event_type"),
                resultSet.getLong("actor_id"),
                resultSet.getString("payload"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getString("integrity_key_id"),
                resultSet.getString("integrity_signature"),
                resultSet.getString("correlation_id"),
                resultSet.getObject("integrity_version", Integer.class)
        );
    }

    private enum FailureResult {
        RETRY_SCHEDULED,
        DEAD_LETTERED,
        IGNORED
    }

    private static final class OutboxPublicationException extends RuntimeException {
        private final Long outboxId;

        private OutboxPublicationException(Long outboxId, RuntimeException cause) {
            super(cause);
            this.outboxId = outboxId;
        }

        private Long outboxId() {
            return outboxId;
        }
    }

    private record PendingEvent(
            Long id,
            String eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            Long actorId,
            String payload,
            OffsetDateTime createdAt,
            String integrityKeyId,
            String integritySignature,
            String correlationId,
            Integer integrityVersion
    ) {
    }
}
