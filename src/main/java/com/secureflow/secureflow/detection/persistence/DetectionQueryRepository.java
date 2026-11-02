package com.secureflow.secureflow.detection.persistence;

import com.secureflow.secureflow.detection.application.DetectionCandidate;
import com.secureflow.secureflow.detection.domain.DetectionRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public class DetectionQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    public DetectionQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DetectionCandidate> findCandidates(Instant from, Instant to) {
        List<DetectionCandidate> candidates = new ArrayList<>();
        candidates.addAll(byActor(DetectionRule.ACTOR_LOGIN_FAILURE_BURST,
                "event_type = 'LOGIN_FAILED' and outcome = 'FAILURE'", 5, from, to));
        candidates.addAll(byIpDistinctActors(from, to));
        candidates.addAll(byActor(DetectionRule.ACTOR_ACCESS_DENIED_BURST,
                "event_type = 'ACCESS_DENIED' and outcome = 'DENIED'", 5, from, to));
        candidates.addAll(byActor(DetectionRule.ACTOR_PRIVILEGED_ACTION_FAILURE,
                "event_type = 'PRIVILEGED_ACTION' and outcome in ('FAILURE', 'DENIED')", 3, from, to));
        return List.copyOf(candidates);
    }

    private List<DetectionCandidate> byActor(
            DetectionRule rule, String fixedPredicate, int threshold, Instant from, Instant to
    ) {
        String sql = """
                select cast(actor_id as varchar) subject_value, count(*) event_count,
                       min(occurred_at) first_event_at, max(occurred_at) last_event_at
                from security_events
                where occurred_at >= ? and occurred_at < ? and %s
                group by actor_id
                having count(*) >= ?
                """.formatted(fixedPredicate);
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> candidate(
                rule,
                resultSet.getString("subject_value"),
                resultSet.getLong("event_count"),
                from,
                to,
                resultSet.getObject("first_event_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("last_event_at", OffsetDateTime.class).toInstant()
        ), Timestamp.from(from), Timestamp.from(to), threshold);
    }

    private List<DetectionCandidate> byIpDistinctActors(Instant from, Instant to) {
        String sql = """
                select source_ip subject_value, count(*) event_count,
                       min(occurred_at) first_event_at, max(occurred_at) last_event_at
                from security_events
                where occurred_at >= ? and occurred_at < ?
                  and event_type = 'LOGIN_FAILED' and outcome = 'FAILURE'
                  and source_ip is not null
                group by source_ip
                having count(distinct actor_id) >= 10
                """;
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> candidate(
                DetectionRule.IP_MULTI_ACCOUNT_LOGIN_FAILURE,
                resultSet.getString("subject_value"),
                resultSet.getLong("event_count"),
                from,
                to,
                resultSet.getObject("first_event_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("last_event_at", OffsetDateTime.class).toInstant()
        ), Timestamp.from(from), Timestamp.from(to));
    }

    private DetectionCandidate candidate(
            DetectionRule rule,
            String subject,
            long count,
            Instant from,
            Instant to,
            Instant first,
            Instant last
    ) {
        return new DetectionCandidate(rule, subject, count, from, to, first, last);
    }
}
