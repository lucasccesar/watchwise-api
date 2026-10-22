package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import com.watchwise.watchwise_api.platformimport.repository.ImportFileCleanupTaskRepository;
import com.watchwise.watchwise_api.platformimport.repository.ImportJobRepository;
import com.watchwise.watchwise_api.platformimport.service.impl.ImportUploadQuotaServiceImpl;
import com.watchwise.watchwise_api.platformimport.storage.ImportFileStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImportTemplateUploadLimitTest {
    private final ImportJobRepository jobs = mock(ImportJobRepository.class);
    private final ImportUploadQuotaServiceImpl service = new ImportUploadQuotaServiceImpl(
            mock(AdvisoryLock.class), mock(JdbcTemplate.class), jobs,
            mock(ImportFileCleanupTaskRepository.class), mock(ImportFileStore.class), new ImportLimits(),
            Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("[reserve] Should Reject Oversized Template - When It Exceeds Five MiB")
    void shouldRejectOversizedTemplateWhenItExceedsFiveMiB() {
        assertThatThrownBy(() -> service.reserve(UUID.randomUUID(), ImportInputKind.PICKS_TEMPLATE,
                ImportSourceFormat.WATCHWISE_TEMPLATE_JSON, null, null, "UTC", UUID.randomUUID().toString(),
                5L * 1024 * 1024 + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Import reservation size is invalid");
    }

    @Test
    @DisplayName("[reserve] Should Accept Five MiB Template - When It Meets The Configured Cap")
    void shouldAcceptFiveMiBTemplateWhenItMeetsTheConfiguredCap() {
        when(jobs.countPendingByOwnerId(any(), any())).thenReturn(0L);
        when(jobs.sumStoredUploadBytesByOwnerId(any())).thenReturn(0L);
        when(jobs.sumStoredUploadBytes()).thenReturn(0L);
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var job = service.reserve(UUID.randomUUID(), ImportInputKind.PICKS_TEMPLATE,
                ImportSourceFormat.WATCHWISE_TEMPLATE_JSON, null, null, "UTC", UUID.randomUUID().toString(),
                5L * 1024 * 1024);

        assertThat(job.getUploadSizeBytes()).isEqualTo(5L * 1024 * 1024);
    }
}
