package com.secureflow.secureflow.detection.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;

@Service
public class DetectionWatermarkService {

    static final String PIPELINE = "MVP_RULES";

    private final JdbcTemplate jdbcTemplate;
    private final DetectionService detectionService;
    private final DetectionProperties properties;

    public DetectionWatermarkService(
            JdbcTemplate jdbcTemplate,
            DetectionService detectionService,
            DetectionProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.detectionService = detectionService;
        this.properties = properties;
    }

    @Transactional
    public DetectionRunResult runOnce(Instant now) {
        Instant eligibleTo = now.minus(properties.getIngestionGrace());
        var windowSize = properties.getWindowSize();
        jdbcTemplate.update("""
                insert into detection_watermarks (pipeline, watermark, updated_at)
                values (?, ?, ?)
                on conflict (pipeline) do nothing
                """, PIPELINE, Timestamp.from(eligibleTo.minus(windowSize)), Timestamp.from(now));
        Instant from = jdbcTemplate.queryForObject("""
                select watermark from detection_watermarks
                where pipeline = ? for update
                """, (resultSet, rowNumber) ->
                resultSet.getObject("watermark", OffsetDateTime.class).toInstant(), PIPELINE);
        if (from == null || from.plus(windowSize).isAfter(eligibleTo)) {
            return new DetectionRunResult(from, from, 0, 0, 0);
        }
        Instant to = from.plus(windowSize);
        DetectionRunResult result = detectionService.detectWindow(from, to);
        jdbcTemplate.update("""
                update detection_watermarks set watermark = ?, updated_at = ? where pipeline = ?
                """, Timestamp.from(to), Timestamp.from(now), PIPELINE);
        return result;
    }
}
