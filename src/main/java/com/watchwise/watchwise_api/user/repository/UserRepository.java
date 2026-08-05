package com.watchwise.watchwise_api.user.repository;

import com.watchwise.watchwise_api.user.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Boolean existsByUsername(String username);

    Boolean existsByEmail(String email);
}
