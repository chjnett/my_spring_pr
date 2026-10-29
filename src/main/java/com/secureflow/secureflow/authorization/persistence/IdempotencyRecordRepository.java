package com.secureflow.secureflow.authorization.persistence;

import com.secureflow.secureflow.authorization.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long>, IdempotencyRecordReservationRepository {

    Optional<IdempotencyRecord> findByUserIdAndRequestScopeAndIdempotencyKey(
            Long userId,
            String requestScope,
            String idempotencyKey
    );
}
