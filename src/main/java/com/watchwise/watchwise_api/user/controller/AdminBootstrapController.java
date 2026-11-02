package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.user.config.AdminBootstrapEnabledCondition;
import com.watchwise.watchwise_api.user.dto.AdminBootstrapRequestDTO;
import com.watchwise.watchwise_api.user.dto.AdminBootstrapResponseDTO;
import com.watchwise.watchwise_api.user.service.AdminBootstrapService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Conditional(AdminBootstrapEnabledCondition.class)
public class AdminBootstrapController {

    private final AdminBootstrapService adminBootstrapService;

    @PostMapping("/admin/bootstrap")
    public ResponseEntity<AdminBootstrapResponseDTO> promoteFirstAdmin(
            @RequestHeader(name = "X-Admin-Bootstrap-Token", required = false) String token,
            @Valid @RequestBody AdminBootstrapRequestDTO request) {
        AdminBootstrapResponseDTO promoted = adminBootstrapService.promoteFirstAdmin(token, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(promoted);
    }
}
