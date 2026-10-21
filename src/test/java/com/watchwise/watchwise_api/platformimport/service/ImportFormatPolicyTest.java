package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImportFormatPolicyTest {
    private final ImportFormatPolicy policy = new ImportFormatPolicy();

    @Test
    @DisplayName("[accepts] Should Accept Approved Matrix - When Kind Format And Platform Match")
    void acceptsShouldAcceptApprovedMatrixWhenKindFormatAndPlatformMatch() {
        assertThat(policy.accepts(ImportInputKind.PLATFORM_ARCHIVE, ImportSourceFormat.TRAKT_ZIP, ImportPlatform.TRAKT)).isTrue();
        assertThat(policy.accepts(ImportInputKind.PLATFORM_ARCHIVE, ImportSourceFormat.LETTERBOXD_ZIP, ImportPlatform.LETTERBOXD)).isTrue();
        assertThat(policy.accepts(ImportInputKind.SINGLE_LIST, ImportSourceFormat.TRAKT_JSON, ImportPlatform.TRAKT)).isTrue();
        assertThat(policy.accepts(ImportInputKind.SINGLE_LIST, ImportSourceFormat.LETTERBOXD_CSV, ImportPlatform.LETTERBOXD)).isTrue();
        assertThat(policy.accepts(ImportInputKind.SINGLE_WATCHLIST, ImportSourceFormat.TRAKT_JSON, ImportPlatform.TRAKT)).isTrue();
        assertThat(policy.accepts(ImportInputKind.SINGLE_WATCHLIST, ImportSourceFormat.LETTERBOXD_CSV, ImportPlatform.LETTERBOXD)).isTrue();
        assertThat(policy.accepts(ImportInputKind.PICKS_TEMPLATE, ImportSourceFormat.WATCHWISE_TEMPLATE_JSON, null)).isTrue();
    }

    @Test
    @DisplayName("[accepts] Should Reject Invalid Combinations - When Any Input Is Null Or Mismatched")
    void acceptsShouldRejectInvalidCombinationsWhenAnyInputIsNullOrMismatched() {
        assertThat(policy.accepts(null, ImportSourceFormat.TRAKT_ZIP, ImportPlatform.TRAKT)).isFalse();
        assertThat(policy.accepts(ImportInputKind.PLATFORM_ARCHIVE, null, ImportPlatform.TRAKT)).isFalse();
        assertThat(policy.accepts(ImportInputKind.PLATFORM_ARCHIVE, ImportSourceFormat.TRAKT_ZIP, null)).isFalse();
        assertThat(policy.accepts(ImportInputKind.PLATFORM_ARCHIVE, ImportSourceFormat.TRAKT_ZIP, ImportPlatform.LETTERBOXD)).isFalse();
        assertThat(policy.accepts(ImportInputKind.SINGLE_LIST, ImportSourceFormat.TRAKT_JSON, ImportPlatform.LETTERBOXD)).isFalse();
        assertThat(policy.accepts(ImportInputKind.PICKS_TEMPLATE, ImportSourceFormat.WATCHWISE_TEMPLATE_JSON, ImportPlatform.TRAKT)).isFalse();
    }
}
