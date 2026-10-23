package com.watchwise.watchwise_api.common.idempotency;

import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class UserActionExecutorImpl implements UserActionExecutor {
    private final JdbcTemplate jdbc;
    private final AdvisoryLock advisoryLock;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ActionResult execute(UUID userId, UserActionOperation operation, String key, String requestHash, Supplier<ActionResult> action) {
        if (userId == null || operation == null || key == null || key.isBlank() || key.length() > 255
                || requestHash == null || requestHash.isBlank() || requestHash.length() > 128 || action == null) {
            throw new IllegalArgumentException("Invalid user action receipt scope");
        }
        advisoryLock.lock("user-action:" + userId + ":" + operation + ":" + key);
        UserActionReceipt existing = find(userId, operation, key);
        if (existing != null) return replay(existing, requestHash);

        ActionResult result = action.get();
        validateResult(operation, result);
        String json = mapper.writeValueAsString(result);
        int inserted = jdbc.update("INSERT INTO user_action_receipts(id,user_id,operation,idempotency_key,request_hash,result_json) VALUES (?,?,?,?,?,?) ON CONFLICT (user_id,operation,idempotency_key) DO NOTHING",
                UUID.randomUUID(), userId, operation.name(), key, requestHash, json);
        if (inserted == 0) return replay(find(userId, operation, key), requestHash);
        return result;
    }

    private UserActionReceipt find(UUID userId, UserActionOperation operation, String key) {
        List<UserActionReceipt> rows = jdbc.query("SELECT * FROM user_action_receipts WHERE user_id = ? AND operation = ? AND idempotency_key = ?",
                (rs, n) -> UserActionReceipt.builder()
                        .id(rs.getObject("id", UUID.class)).userId(userId).operation(operation).idempotencyKey(key)
                        .requestHash(rs.getString("request_hash")).resultJson(rs.getString("result_json")).build(),
                userId, operation.name(), key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ActionResult replay(UserActionReceipt receipt, String requestHash) {
        if (receipt == null || !receipt.getRequestHash().equals(requestHash)) throw new ConflictException("Idempotency key already used with different request");
        return mapper.readValue(receipt.getResultJson(), ActionResult.class);
    }

    private void validateResult(UserActionOperation operation, ActionResult result) {
        if (result == null || result.resourceIds() == null || result.resourceIds().isEmpty()
                || result.resourceIds().stream().anyMatch(id -> id == null) || result.version() < 0
                || (operation == UserActionOperation.CONTENT_REVIEW_PUBLICATION && result.resourceIds().size() != 1)) {
            throw new IllegalArgumentException("Invalid user action result");
        }
    }
}
