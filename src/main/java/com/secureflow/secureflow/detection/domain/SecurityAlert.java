package com.secureflow.secureflow.detection.domain;

import com.secureflow.secureflow.detection.application.DetectionCandidate;
import com.secureflow.secureflow.detection.exception.InvalidAlertTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "security_alerts")
public class SecurityAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, unique = true, updatable = false)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_code", nullable = false, length = 50, updatable = false)
    private DetectionRule ruleCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private AlertSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlertStatus status;

    @Column(name = "subject_type", nullable = false, length = 20, updatable = false)
    private String subjectType;

    @Column(name = "subject_value", nullable = false, length = 100, updatable = false)
    private String subjectValue;

    @Column(name = "event_count", nullable = false, updatable = false)
    private long eventCount;

    @Column(name = "window_start", nullable = false, updatable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false, updatable = false)
    private Instant windowEnd;

    @Column(name = "first_event_at", nullable = false, updatable = false)
    private Instant firstEventAt;

    @Column(name = "last_event_at", nullable = false, updatable = false)
    private Instant lastEventAt;

    @Column(name = "assignee_id")
    private Long assigneeId;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SecurityAlert() {
    }

    public SecurityAlert(String fingerprint, DetectionCandidate candidate, Instant now) {
        this.fingerprint = fingerprint;
        this.ruleCode = candidate.rule();
        this.severity = candidate.rule().severity();
        this.status = AlertStatus.OPEN;
        this.subjectType = candidate.rule().subjectType();
        this.subjectValue = candidate.subjectValue();
        this.eventCount = candidate.eventCount();
        this.windowStart = candidate.windowStart();
        this.windowEnd = candidate.windowEnd();
        this.firstEventAt = candidate.firstEventAt();
        this.lastEventAt = candidate.lastEventAt();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void assign(Long assigneeId, Long actorId, Instant now) {
        if (status == AlertStatus.RESOLVED) {
            throw new InvalidAlertTransitionException("해결된 경보에는 담당자를 변경할 수 없습니다.");
        }
        if (assigneeId == null || assigneeId < 1) {
            throw new IllegalArgumentException("assigneeId는 양수여야 합니다.");
        }
        this.assigneeId = assigneeId;
        this.updatedBy = actorId;
        this.status = AlertStatus.INVESTIGATING;
        this.updatedAt = now;
    }

    public void resolve(String note, Long actorId, Instant now) {
        if (status != AlertStatus.INVESTIGATING) {
            throw new InvalidAlertTransitionException("조사 중인 경보만 해결할 수 있습니다.");
        }
        if (note == null || note.isBlank() || note.length() > 1000) {
            throw new IllegalArgumentException("resolutionNote는 1자 이상 1000자 이하여야 합니다.");
        }
        this.status = AlertStatus.RESOLVED;
        this.resolutionNote = note;
        this.updatedBy = actorId;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getFingerprint() { return fingerprint; }
    public DetectionRule getRuleCode() { return ruleCode; }
    public AlertSeverity getSeverity() { return severity; }
    public AlertStatus getStatus() { return status; }
    public String getSubjectType() { return subjectType; }
    public String getSubjectValue() { return subjectValue; }
    public long getEventCount() { return eventCount; }
    public Instant getWindowStart() { return windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public Instant getFirstEventAt() { return firstEventAt; }
    public Instant getLastEventAt() { return lastEventAt; }
    public Long getAssigneeId() { return assigneeId; }
    public String getResolutionNote() { return resolutionNote; }
    public Long getUpdatedBy() { return updatedBy; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
