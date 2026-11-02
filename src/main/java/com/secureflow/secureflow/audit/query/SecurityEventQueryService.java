package com.secureflow.secureflow.audit.query;

import com.secureflow.secureflow.audit.domain.AuditEventType;
import com.secureflow.secureflow.audit.ingest.ExternalSecurityEventType;
import com.secureflow.secureflow.audit.ingest.SecurityEventNormalizer;
import com.secureflow.secureflow.audit.ingest.SecurityEventOutcome;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class SecurityEventQueryService {

    private static final Duration DEFAULT_RANGE = Duration.ofHours(24);
    private static final Duration MAX_RANGE = Duration.ofDays(31);
    private static final int DEFAULT_SIZE = 50;
    private static final int MAX_SIZE = 200;
    private static final Set<String> ALLOWED_EVENT_TYPES = allowedEventTypes();

    private final JdbcClient jdbcClient;
    private final SecurityEventCursorCodec cursorCodec;
    private final SecurityEventNormalizer normalizer;
    private final ObjectMapper objectMapper;

    public SecurityEventQueryService(
            JdbcTemplate jdbcTemplate,
            SecurityEventCursorCodec cursorCodec,
            SecurityEventNormalizer normalizer,
            ObjectMapper objectMapper
    ) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.cursorCodec = cursorCodec;
        this.normalizer = normalizer;
        this.objectMapper = objectMapper;
    }

    public SecurityEventPage search(
            Instant from,
            Instant to,
            String eventType,
            Long actorId,
            String sourceIp,
            String outcome,
            Integer requestedSize,
            String cursor
    ) {
        Instant effectiveTo = to == null ? Instant.now() : to;
        Instant effectiveFrom = from == null ? effectiveTo.minus(DEFAULT_RANGE) : from;
        validateRange(effectiveFrom, effectiveTo);
        int size = requestedSize == null ? DEFAULT_SIZE : requestedSize;
        if (size < 1 || size > MAX_SIZE) {
            throw new SecurityEventQueryException("size는 1 이상 200 이하여야 합니다.");
        }
        if (eventType != null && !ALLOWED_EVENT_TYPES.contains(eventType)) {
            throw new SecurityEventQueryException("허용되지 않은 eventType입니다.");
        }
        if (actorId != null && actorId < 1) {
            throw new SecurityEventQueryException("actorId는 양수여야 합니다.");
        }
        String normalizedIp = null;
        if (sourceIp != null) {
            normalizedIp = normalizer.normalizeIpAddress(sourceIp);
            if (normalizedIp == null) {
                throw new SecurityEventQueryException("sourceIp는 IPv4 또는 IPv6 주소여야 합니다.");
            }
        }
        if (outcome != null) {
            try {
                SecurityEventOutcome.valueOf(outcome);
            } catch (IllegalArgumentException exception) {
                throw new SecurityEventQueryException("허용되지 않은 outcome입니다.");
            }
        }
        SecurityEventCursorCodec.Cursor decodedCursor = cursor == null
                ? null
                : cursorCodec.decode(cursor);

        StringBuilder sql = new StringBuilder("""
                select id, event_id, event_type, actor_id, source, source_ip, outcome,
                       occurred_at, ingested_at, correlation_id, payload
                from security_events
                where occurred_at >= ? and occurred_at <= ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(Timestamp.from(effectiveFrom));
        arguments.add(Timestamp.from(effectiveTo));
        if (eventType != null) {
            sql.append(" and event_type = ?");
            arguments.add(eventType);
        }
        if (actorId != null) {
            sql.append(" and actor_id = ?");
            arguments.add(actorId);
        }
        if (normalizedIp != null) {
            sql.append(" and source_ip = ?");
            arguments.add(normalizedIp);
        }
        if (outcome != null) {
            sql.append(" and outcome = ?");
            arguments.add(outcome);
        }
        if (decodedCursor != null) {
            sql.append(" and (occurred_at < ? or (occurred_at = ? and id < ?))");
            Timestamp cursorTime = Timestamp.from(decodedCursor.occurredAt());
            arguments.add(cursorTime);
            arguments.add(cursorTime);
            arguments.add(decodedCursor.id());
        }
        sql.append(" order by occurred_at desc, id desc limit ?");
        arguments.add(size + 1);

        List<SecurityEventView> rows = jdbcClient.sql(sql.toString())
                .params(arguments)
                .query(this::mapEvent)
                .list();
        boolean hasNext = rows.size() > size;
        List<SecurityEventView> pageEvents = hasNext
                ? new ArrayList<>(rows.subList(0, size))
                : rows;
        String nextCursor = null;
        if (hasNext) {
            SecurityEventView last = pageEvents.getLast();
            nextCursor = cursorCodec.encode(last.occurredAt(), last.id());
        }
        return new SecurityEventPage(
                effectiveFrom, effectiveTo, size, nextCursor, List.copyOf(pageEvents)
        );
    }

    private void validateRange(Instant from, Instant to) {
        if (from.isAfter(to)) {
            throw new SecurityEventQueryException("from은 to보다 늦을 수 없습니다.");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new SecurityEventQueryException("조회 기간은 최대 31일입니다.");
        }
    }

    private SecurityEventView mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SecurityEventView(
                resultSet.getLong("id"),
                resultSet.getString("event_id"),
                resultSet.getString("event_type"),
                resultSet.getLong("actor_id"),
                resultSet.getString("source"),
                resultSet.getString("source_ip"),
                resultSet.getString("outcome"),
                resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("ingested_at", OffsetDateTime.class).toInstant(),
                resultSet.getString("correlation_id"),
                payload(resultSet.getString("payload"))
        );
    }

    private JsonNode payload(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException exception) {
            return objectMapper.getNodeFactory().stringNode(value);
        }
    }

    private static Set<String> allowedEventTypes() {
        Set<String> values = new HashSet<>();
        for (AuditEventType type : AuditEventType.values()) {
            values.add(type.name());
        }
        for (ExternalSecurityEventType type : ExternalSecurityEventType.values()) {
            values.add(type.name());
        }
        return Set.copyOf(values);
    }
}
