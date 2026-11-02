package com.secureflow.secureflow.audit.ingest;

public record SecurityEventItemResult(
        int index,
        String eventId,
        SecurityEventIngestStatus status,
        String errorCode,
        String message
) {
    public static SecurityEventItemResult accepted(int index, String eventId) {
        return new SecurityEventItemResult(index, eventId, SecurityEventIngestStatus.ACCEPTED, null, null);
    }

    public static SecurityEventItemResult duplicate(int index, String eventId) {
        return new SecurityEventItemResult(index, eventId, SecurityEventIngestStatus.DUPLICATE, null, null);
    }

    public static SecurityEventItemResult rejected(int index, String eventId, String code, String message) {
        return new SecurityEventItemResult(index, eventId, SecurityEventIngestStatus.REJECTED, code, message);
    }
}
