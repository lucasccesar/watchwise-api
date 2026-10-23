package com.watchwise.watchwise_api.common.idempotency;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public interface UserActionExecutor {
    ActionResult execute(UUID userId, UserActionOperation operation, String key, String requestHash, Supplier<ActionResult> action);

    record ActionResult(List<UUID> resourceIds, long version) {}
}
