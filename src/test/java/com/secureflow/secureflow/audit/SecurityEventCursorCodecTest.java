package com.secureflow.secureflow.audit;

import com.secureflow.secureflow.audit.query.SecurityEventCursorCodec;
import com.secureflow.secureflow.audit.query.SecurityEventQueryException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityEventCursorCodecTest {

    private final SecurityEventCursorCodec codec = new SecurityEventCursorCodec();

    @Test
    void roundTripsTimestampAndId() {
        Instant occurredAt = Instant.parse("2026-11-02T08:00:00.123456Z");

        SecurityEventCursorCodec.Cursor decoded = codec.decode(codec.encode(occurredAt, 42L));

        assertThat(decoded.occurredAt()).isEqualTo(occurredAt);
        assertThat(decoded.id()).isEqualTo(42L);
    }

    @Test
    void rejectsMalformedCursor() {
        assertThatThrownBy(() -> codec.decode("not-a-cursor"))
                .isInstanceOf(SecurityEventQueryException.class);
    }
}
