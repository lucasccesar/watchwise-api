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

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForLifecycle(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForListCreation(@Param("id") UUID id);

    Optional<User> findByUsernameIgnoreCase(String username);

    @Modifying
    @Query("update User u set u.sessionsInvalidatedAt = :at, u.authVersion = u.authVersion + 1 where u.id = :userId")
    void markSessionsInvalidatedAt(@Param("userId") UUID userId, @Param("at") LocalDateTime at);

    Optional<SessionsInvalidatedAtView> findSessionsInvalidatedAtById(UUID id);

    interface SessionsInvalidatedAtView {
        UUID getId();
        LocalDateTime getSessionsInvalidatedAt();
        long getAuthVersion();
    }

    Optional<User> findByUsernameIgnoreCaseOrEmailIgnoreCase(String username, String email);

    Optional<User> findByEmailIgnoreCase(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE LOWER(u.email) = LOWER(:email)")
    Optional<User> findByEmailIgnoreCaseForUpdate(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE LOWER(u.email) = LOWER(:email) OR LOWER(u.pendingEmail) = LOWER(:email)")
    Optional<User> findByEmailOrPendingEmailIgnoreCaseForUpdate(@Param("email") String email);

    @Query("""
        SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END
        FROM User u
        WHERE u.id <> :userId
          AND (LOWER(u.email) = LOWER(:email) OR LOWER(u.pendingEmail) = LOWER(:email))
        """)
    boolean existsEmailOrPendingEmailByAnotherUser(
            @Param("userId") UUID userId,
            @Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.emailConfirmationTokenHash = :tokenHash")
    Optional<User> findByEmailConfirmationTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Query("SELECT u.pendingEmail FROM User u WHERE u.emailConfirmationTokenHash = :tokenHash")
    Optional<String> findPendingEmailByEmailConfirmationTokenHash(@Param("tokenHash") String tokenHash);

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
