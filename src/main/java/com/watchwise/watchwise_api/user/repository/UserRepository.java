package com.watchwise.watchwise_api.user.repository;

import com.watchwise.watchwise_api.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Boolean existsByUsername(String username);

    Boolean existsByEmail(String email);

    Optional<User> findByUsernameIgnoreCaseOrEmailIgnoreCase(String username, String email);

    @Query(
            value = """
        SELECT u FROM User u
        WHERE LOWER(u.username) LIKE LOWER(CONCAT(:username, '%'))
        AND (:onlyPublic = FALSE OR u.isProfilePublic = TRUE)
        ORDER BY
            CASE WHEN LOWER(u.username) = LOWER(:username) THEN 0 ELSE 1 END,
            u.username ASC
        """,
            countQuery = """
        SELECT COUNT(u) FROM User u
        WHERE LOWER(u.username) LIKE LOWER(CONCAT(:username, '%'))
        AND (:onlyPublic = FALSE OR u.isProfilePublic = TRUE)
        """
    )
    Page<User> findByUsernameStartingWithIgnoreCase(
            @Param("username") String username,
            @Param("onlyPublic") boolean onlyPublic,
            Pageable pageable
    );
}
