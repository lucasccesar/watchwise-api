package com.watchwise.watchwise_api.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface UserActionReceiptRepository extends JpaRepository<UserActionReceipt, UUID> {
    Optional<UserActionReceipt> findByUserIdAndOperationAndIdempotencyKey(UUID userId, UserActionOperation operation, String idempotencyKey);
}
