package com.secureflow.secureflow.audit.query;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

@Component
public class SecurityEventCursorCodec {

    public String encode(Instant occurredAt, long id) {
        String value = occurredAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public Cursor decode(String cursor) {
        try {
            String value = new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8
            );
            int separator = value.lastIndexOf('|');
            if (separator < 1) {
                throw new IllegalArgumentException();
            }
            Instant occurredAt = Instant.parse(value.substring(0, separator));
            long id = Long.parseLong(value.substring(separator + 1));
            if (id < 1) {
                throw new IllegalArgumentException();
            }
            return new Cursor(occurredAt, id);
        } catch (IllegalArgumentException exception) {
            throw new SecurityEventQueryException("유효하지 않은 cursor입니다.");
        }
    }

    public record Cursor(Instant occurredAt, long id) {
    }
}
