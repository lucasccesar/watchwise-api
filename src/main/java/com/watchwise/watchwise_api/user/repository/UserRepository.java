package com.watchwise.watchwise_api.user.repository;

import com.watchwise.watchwise_api.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsernameIgnoreCase(String username);

    @Modifying
    @Query("update User u set u.sessionsInvalidatedAt = :at where u.id = :userId")
    void markSessionsInvalidatedAt(@Param("userId") UUID userId, @Param("at") LocalDateTime at);

    Optional<User> findByUsernameIgnoreCaseOrEmailIgnoreCase(String username, String email);

    Optional<User> findByEmailIgnoreCase(String email);

    @Query(
            value = """
        SELECT u FROM User u
        WHERE LOWER(u.username) LIKE LOWER(CONCAT(:escapedUsername, '%')) ESCAPE '\\'
        ORDER BY
            CASE WHEN LOWER(u.username) = LOWER(:username) THEN 0 ELSE 1 END,
            u.username ASC
        """,
            countQuery = """
        SELECT COUNT(u) FROM User u
        WHERE LOWER(u.username) LIKE LOWER(CONCAT(:escapedUsername, '%')) ESCAPE '\\'
        """
    )
    Page<User> findByUsernameStartingWithIgnoreCase(
            @Param("username") String username,
            @Param("escapedUsername") String escapedUsername,
            Pageable pageable
    );
}
