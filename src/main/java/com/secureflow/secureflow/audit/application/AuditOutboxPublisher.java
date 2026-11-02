package com.secureflow.secureflow.audit.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class AuditOutboxPublisher {

    private final JdbcTemplate jdbcTemplate;

    public AuditOutboxPublisher(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * row lock 선점, 보안 이벤트 INSERT, Outbox 완료 표시를 한 트랜잭션으로 처리한다.
     * 커밋 전 장애는 모두 롤백되고, event_id UNIQUE가 재시도 중복을 막는다.
     */
    @Transactional
    public int publishBatch(int batchSize) {
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("batchSize는 1 이상 1000 이하여야 합니다.");
        }
        List<PendingEvent> events = jdbcTemplate.query("""
                select id, event_id, aggregate_type, aggregate_id, event_type,
                       actor_id, payload, created_at, integrity_key_id, integrity_signature
                from audit_outbox
                where status = 'PENDING'
                order by id
                limit ?
                for update skip locked
                """, this::mapPendingEvent, batchSize);

        OffsetDateTime publishedAt = OffsetDateTime.now(ZoneOffset.UTC);
        for (PendingEvent event : events) {
            jdbcTemplate.update("""
                    insert into security_events
                        (event_id, aggregate_type, aggregate_id, event_type, actor_id,
                         payload, occurred_at, ingested_at, integrity_key_id, integrity_signature)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (event_id) do nothing
                    """,
                    event.eventId(), event.aggregateType(), event.aggregateId(), event.eventType(),
                    event.actorId(), event.payload(), event.createdAt(), publishedAt,
                    event.integrityKeyId(), event.integritySignature()
            );
            jdbcTemplate.update("""
                    update audit_outbox
                    set status = 'PUBLISHED', published_at = ?
                    where id = ?
                    """, publishedAt, event.id());
        }
        return events.size();
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
                resultSet.getString("integrity_signature")
        );
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
            String integritySignature
    ) {
    }
}
