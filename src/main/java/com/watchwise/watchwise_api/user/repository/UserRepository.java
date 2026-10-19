package com.watchwise.watchwise_api.user.repository;

import com.watchwise.watchwise_api.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForFollowStatus(@Param("id") UUID id);

    Optional<User> findByUsernameIgnoreCase(String username);

    @Modifying
    @Query("update User u set u.sessionsInvalidatedAt = :at where u.id = :userId")
    void markSessionsInvalidatedAt(@Param("userId") UUID userId, @Param("at") LocalDateTime at);

    Optional<SessionsInvalidatedAtView> findSessionsInvalidatedAtById(UUID id);

    interface SessionsInvalidatedAtView {
        UUID getId();
        LocalDateTime getSessionsInvalidatedAt();
    }

    Optional<User> findByUsernameIgnoreCaseOrEmailIgnoreCase(String username, String email);

    Optional<User> findByEmailIgnoreCase(String email);

    @Query(
            value = """
        SELECT u FROM User u
        WHERE LOWER(u.username) LIKE CONCAT(LOWER(:escapedUsername), '%') ESCAPE '\\'
        ORDER BY
            CASE WHEN LOWER(u.username) = LOWER(:username) THEN 0 ELSE 1 END,
            u.username ASC
        """,
            countQuery = """
        SELECT COUNT(u) FROM User u
        WHERE LOWER(u.username) LIKE CONCAT(LOWER(:escapedUsername), '%') ESCAPE '\\'
        """
    )
    Page<User> findByUsernameStartingWithIgnoreCase(
            @Param("username") String username,
            @Param("escapedUsername") String escapedUsername,
            Pageable pageable
    );

    @Query("""
        SELECT u FROM User u
        WHERE LOWER(u.username) LIKE CONCAT(LOWER(:escapedUsername), '%') ESCAPE '\\'
        ORDER BY
            CASE WHEN LOWER(u.username) = LOWER(:username) THEN 0 ELSE 1 END,
            u.username ASC
        """)
    Slice<User> findByUsernameStartingWithIgnoreCaseForSearch(
            @Param("username") String username,
            @Param("escapedUsername") String escapedUsername,
            Pageable pageable
    );

    @Query(value = """
        SELECT u FROM User u
        WHERE LOWER(u.username) LIKE CONCAT('%', LOWER(:escapedQuery), '%') ESCAPE '\\'
           OR LOWER(u.name) LIKE CONCAT('%', LOWER(:escapedQuery), '%') ESCAPE '\\'
        ORDER BY
            CASE
                WHEN LOWER(u.username) = LOWER(:query) THEN 0
                WHEN LOWER(u.name) = LOWER(:query) THEN 1
                WHEN LOWER(u.username) LIKE CONCAT(LOWER(:escapedQuery), '%') ESCAPE '\\' THEN 2
                WHEN LOWER(u.name) LIKE CONCAT(LOWER(:escapedQuery), '%') ESCAPE '\\' THEN 3
                ELSE 4
            END,
            LOWER(u.name), LOWER(u.username), u.id
        """,
            countQuery = """
        SELECT COUNT(u) FROM User u
        WHERE LOWER(u.username) LIKE CONCAT('%', LOWER(:escapedQuery), '%') ESCAPE '\\'
           OR LOWER(u.name) LIKE CONCAT('%', LOWER(:escapedQuery), '%') ESCAPE '\\'
        """)
    Page<User> searchByNameOrUsernameForView(
            @Param("query") String query,
            @Param("escapedQuery") String escapedQuery,
            Pageable pageable);
}
