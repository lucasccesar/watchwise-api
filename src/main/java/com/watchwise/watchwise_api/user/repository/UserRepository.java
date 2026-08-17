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

    Optional<User> findByUsernameIgnoreCase(String username);

    Optional<User> findByUsernameIgnoreCaseOrEmailIgnoreCase(String username, String email);

    Optional<User> findByEmailIgnoreCase(String email);

    @Query(
            value = """
        SELECT u FROM User u
        WHERE LOWER(u.username) LIKE LOWER(CONCAT(:escapedUsername, '%')) ESCAPE '\\'
        AND (:onlyPublic = FALSE OR u.isProfilePublic = TRUE)
        ORDER BY
            CASE WHEN LOWER(u.username) = LOWER(:username) THEN 0 ELSE 1 END,
            u.username ASC
        """,
            countQuery = """
        SELECT COUNT(u) FROM User u
        WHERE LOWER(u.username) LIKE LOWER(CONCAT(:escapedUsername, '%')) ESCAPE '\\'
        AND (:onlyPublic = FALSE OR u.isProfilePublic = TRUE)
        """
    )
    Page<User> findByUsernameStartingWithIgnoreCase(
            @Param("username") String username,
            @Param("escapedUsername") String escapedUsername,
            @Param("onlyPublic") boolean onlyPublic,
            Pageable pageable
    );
}
