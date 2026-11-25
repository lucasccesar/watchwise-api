package com.watchwise.watchwise_api.follower.repository;

import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.user.dto.PublicUserDTO;
import com.watchwise.watchwise_api.user.entity.User;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FollowGraphProjectionTest {

    @Test
    void hibernateAcceptsBothScalarProjectionsAndCountQueriesWithoutJdbc() throws Exception {
        try (var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .build();
             SessionFactory factory = new MetadataSources(registry)
                     .addAnnotatedClass(User.class)
                     .addAnnotatedClass(Follower.class)
                     .buildMetadata()
                     .buildSessionFactory();
             var session = factory.openSession()) {
            assertProjection(session, "findAcceptedFollowerPreviews", "f.follower", "f.followed.id");
            assertProjection(session, "findAcceptedFollowingPreviews", "f.followed", "f.follower.id");
        }
    }

    private void assertProjection(Session session, String method, String joinedUser, String target)
            throws NoSuchMethodException {
        Query query = FollowerRepository.class.getMethod(method, UUID.class, Pageable.class)
                .getAnnotation(Query.class);

        session.createSelectionQuery(query.value(), PublicUserDTO.class);
        session.createSelectionQuery(query.countQuery(), Long.class);

        assertThat(query.value().replaceAll("\\s+", " ").replace("( ", "(").trim()).isEqualTo(
                "SELECT new " + PublicUserDTO.class.getName() + "(u.id, u.username, u.name, u.description, "
                        + "u.profilePicture, u.isProfilePublic, u.createdAt) FROM Follower f JOIN " + joinedUser
                        + " u WHERE " + target + " = :targetId AND f.status = "
                        + "com.watchwise.watchwise_api.follower.entity.FollowStatus.ACCEPTED "
                        + "ORDER BY f.createdAt DESC, f.id DESC");
        assertThat(query.countQuery().replaceAll("\\s+", " ").trim()).isEqualTo(
                "SELECT COUNT(f) FROM Follower f WHERE " + target + " = :targetId AND f.status = "
                        + "com.watchwise.watchwise_api.follower.entity.FollowStatus.ACCEPTED");
    }
}
