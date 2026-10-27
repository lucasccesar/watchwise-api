package com.watchwise.watchwise_api.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LockFailureApiTest {
    @RestController
    static class ContendedDeletion {
        @DeleteMapping("/test/account")
        void deleteAccount() { throw new CannotAcquireLockException("private SQL details"); }
    }

    @Test
    void knownLockTimeoutReturnsRetryable503WithoutSqlDetails() throws Exception {
        MockMvcBuilders.standaloneSetup(new ContendedDeletion())
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(delete("/test/account"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Resource is busy; retry the request"));
    }
}
