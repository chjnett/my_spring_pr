package com.secureflow.secureflow.authorization.persistence;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** PostgreSQL의 ON CONFLICT로 멱등성 키를 원자적으로 선점한다. */
public interface IdempotencyRecordReservationRepository {

    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into idempotency_records
                (user_id, request_scope, idempotency_key, request_hash, status, created_at)
            values
                (:userId, :requestScope, :idempotencyKey, :requestHash, 'PROCESSING', current_timestamp)
            on conflict (user_id, request_scope, idempotency_key) do nothing
            """, nativeQuery = true)
    int reserve(
            @Param("userId") Long userId,
            @Param("requestScope") String requestScope,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash
    );
}
