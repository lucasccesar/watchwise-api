package com.watchwise.watchwise_api.user.repository;

import com.watchwise.watchwise_api.user.entity.PendingRegistration;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PendingRegistrationRepository extends JpaRepository<PendingRegistration, UUID> {

    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(LOWER(:email), 0))", nativeQuery = true)
    Object lockEmail(@Param("email") String email);

    long countByEmailIgnoreCaseAndExpiresAtAfter(String email, LocalDateTime now);

    @Query("SELECT r.email FROM PendingRegistration r WHERE r.confirmationTokenHash = :tokenHash")
    Optional<String> findEmailByConfirmationTokenHash(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PendingRegistration r WHERE r.confirmationTokenHash = :tokenHash")
    Optional<PendingRegistration> findByConfirmationTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT r FROM PendingRegistration r
        WHERE LOWER(r.email) = LOWER(:email)
          AND r.registrationProofHash = :proofHash
          AND r.expiresAt > :now
        """)
    Optional<PendingRegistration> findByEmailAndRegistrationProofHashForUpdate(
            @Param("email") String email,
            @Param("proofHash") String proofHash,
            @Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM PendingRegistration r WHERE LOWER(r.email) = LOWER(:email)")
    int deleteByEmailIgnoreCase(@Param("email") String email);

    @Modifying
    @Query("DELETE FROM PendingRegistration r WHERE r.expiresAt <= :now")
    int deleteExpired(@Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM PendingRegistration r WHERE LOWER(r.email) = LOWER(:email) AND r.expiresAt <= :now")
    int deleteExpiredForEmail(@Param("email") String email, @Param("now") LocalDateTime now);
}
