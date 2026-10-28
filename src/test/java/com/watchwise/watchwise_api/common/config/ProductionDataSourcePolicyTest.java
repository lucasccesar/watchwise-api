package com.watchwise.watchwise_api.common.config;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionDataSourcePolicyTest {
    @Test
    void acceptsRequiredEncryptionAndBatchedInsertRewrite() {
        for (String sslmode : new String[]{"require", "verify-ca", "verify-full"}) {
            HikariConfig config = validConfig("jdbc:postgresql://localhost:5432/watchwise?sslmode="
                    + sslmode + "&reWriteBatchedInserts=true");
            assertThatCode(() -> ProductionDataSourcePolicy.validate(config)).doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsUrlThatDisablesEncryptionDespiteDatasourceProperty() {
        HikariConfig config = validConfig(
                "jdbc:postgresql://private-host/watchwise?sslmode=disable&reWriteBatchedInserts=true");
        config.addDataSourceProperty("sslmode", "require");

        assertThatThrownBy(() -> ProductionDataSourcePolicy.validate(config))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(failure -> {
                    assertThat(failure.getMessage()).contains("sslmode")
                            .doesNotContain("private-host", "super-secret");
                });
    }

    @Test
    void rejectsMissingTlsAndDisabledBatchRewrite() {
        HikariConfig noTls = validConfig("jdbc:postgresql://localhost/watchwise?reWriteBatchedInserts=true");
        noTls.getDataSourceProperties().remove("sslmode");
        assertThatThrownBy(() -> ProductionDataSourcePolicy.validate(noTls))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("sslmode");

        HikariConfig noBatch = validConfig(
                "jdbc:postgresql://localhost/watchwise?sslmode=require&reWriteBatchedInserts=false");
        assertThatThrownBy(() -> ProductionDataSourcePolicy.validate(noBatch))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("reWriteBatchedInserts");
    }

    @Test
    void rejectsPoolLimitsOutsideTheConfiguredRange() {
        HikariConfig config = validConfig(
                "jdbc:postgresql://localhost/watchwise?sslmode=require&reWriteBatchedInserts=true");
        config.setMinimumIdle(config.getMaximumPoolSize() + 1);

        assertThatThrownBy(() -> ProductionDataSourcePolicy.validate(config))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Hikari");
    }

    private HikariConfig validConfig(String url) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername("watchwise-test");
        config.setPassword("super-secret");
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(5000);
        config.addDataSourceProperty("sslmode", "require");
        config.addDataSourceProperty("reWriteBatchedInserts", "true");
        return config;
    }
}
