package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.user.dto.AdminTestUserCreatedDTO;
import com.watchwise.watchwise_api.user.dto.AdminTestUserCreationDTO;
import com.watchwise.watchwise_api.user.service.TestUserProvisioningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.auth.test-user-provisioning", name = "enabled", havingValue = "true")
public class AdminTestUserController {

    private final TestUserProvisioningService testUserProvisioningService;

    @PostMapping("/admin/test-users")
    public ResponseEntity<AdminTestUserCreatedDTO> createTestUser(
            @Valid @RequestBody AdminTestUserCreationDTO request) {
        UUID adminId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        AdminTestUserCreatedDTO created = testUserProvisioningService.createTestUser(adminId, request);
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .body(created);
    }
}
