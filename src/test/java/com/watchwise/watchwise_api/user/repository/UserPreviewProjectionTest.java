package com.watchwise.watchwise_api.user.repository;

import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.userblock.entity.UserBlock;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

class UserPreviewProjectionTest {

    @Test
    void shouldRenderOnlyPreviewColumnsWithPostgreSqlDialectBeforePreparingStatement() throws SQLException {
        DataSource disconnectedDataSource = mock(DataSource.class);
        Connection disconnectedConnection = mock(Connection.class);
        when(disconnectedDataSource.getConnection()).thenReturn(disconnectedConnection);
        CapturedSql capture = new CapturedSql();
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("jakarta.persistence.nonJtaDataSource", disconnectedDataSource)
                .applySetting("hibernate.dialect", PostgreSQLDialect.class)
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .applySetting("hibernate.session_factory.statement_inspector", capture)
                .build();
        try (var factory = new MetadataSources(registry).addAnnotatedClass(User.class)
                .addAnnotatedClass(Follower.class).addAnnotatedClass(UserBlock.class)
                .buildMetadata().buildSessionFactory();
             var session = factory.openSession()) {
            var repositories = new JpaRepositoryFactory(session);
            UserRepository repository = repositories.getRepository(UserRepository.class);
            List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());

            assertThatThrownBy(() -> repository.findPreviewsByIdIn(ids)).isInstanceOf(StopBeforeJdbc.class);
            assertThat(capture.sql).matches("select (\\w+)\\.id,\\1\\.username,\\1\\.name,"
                    + "\\1\\.profile_picture,\\1\\.is_profile_public from users \\1 where \\1\\.id in \\(\\?,\\?\\)");

            assertThatThrownBy(() -> repository.findAllById(ids)).isInstanceOf(StopBeforeJdbc.class);
            assertThat(capture.sql).contains(".id", ".username", ".name", ".profile_picture", ".is_profile_public",
                    ".email", ".password", ".description");

            assertThatThrownBy(() -> repository.findSessionIdentityById(ids.getFirst()))
                    .isInstanceOf(StopBeforeJdbc.class);
            assertThat(capture.sql).matches("select (\\w+)\\.id,\\1\\.username,\\1\\.email,\\1\\.name,"
                    + "\\1\\.description,\\1\\.profile_picture,\\1\\.banner,\\1\\.is_profile_public,"
                    + "\\1\\.preferred_language,\\1\\.preferred_region from users \\1 where \\1\\.id=\\?");

            FollowerRepository followers = repositories.getRepository(FollowerRepository.class);
            assertThatThrownBy(() -> followers.findVisibleFollowedIdsByFollowerId(UUID.randomUUID()))
                    .isInstanceOf(StopBeforeJdbc.class);
            assertThat(capture.sql).contains(".status='ACCEPTED'", "not exists")
                    .containsPattern("\\.blocker_id=\\? and \\w+\\.blocked_id=\\w+\\.followed_id")
                    .containsPattern("\\.blocker_id=\\w+\\.followed_id and \\w+\\.blocked_id=\\?");
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
        assertThat(mockingDetails(disconnectedConnection).getInvocations())
                .noneMatch(invocation -> invocation.getMethod().getName().startsWith("prepare")
                        || invocation.getMethod().getName().equals("createStatement"));
    }

    private static final class CapturedSql implements StatementInspector {
        private String sql;

        @Override
        public String inspect(String sql) {
            this.sql = sql;
            throw new StopBeforeJdbc();
        }
    }

    private static final class StopBeforeJdbc extends RuntimeException {
    }
}
