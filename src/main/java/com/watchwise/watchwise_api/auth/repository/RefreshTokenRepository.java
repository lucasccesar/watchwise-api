package com.watchwise.watchwise_api.auth.repository;

import com.watchwise.watchwise_api.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
}
