package com.watchwise.watchwise_api.user.service;

import com.watchwise.watchwise_api.user.dto.AdminBootstrapRequestDTO;
import com.watchwise.watchwise_api.user.dto.AdminBootstrapResponseDTO;

public interface AdminBootstrapService {
    AdminBootstrapResponseDTO promoteFirstAdmin(String suppliedToken, AdminBootstrapRequestDTO request);
}
