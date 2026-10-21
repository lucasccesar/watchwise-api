package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.platformimport.model.ImportInputKind;
import com.watchwise.watchwise_api.platformimport.model.ImportPlatform;
import com.watchwise.watchwise_api.platformimport.model.ImportSourceFormat;

public class ImportFormatPolicy {
    public boolean accepts(ImportInputKind kind, ImportSourceFormat format, ImportPlatform platform) {
        if (kind == null || format == null) return false;
        return switch (kind) {
            case PLATFORM_ARCHIVE -> (format == ImportSourceFormat.TRAKT_ZIP && platform == ImportPlatform.TRAKT)
                    || (format == ImportSourceFormat.LETTERBOXD_ZIP && platform == ImportPlatform.LETTERBOXD);
            case SINGLE_LIST, SINGLE_WATCHLIST -> (format == ImportSourceFormat.TRAKT_JSON && platform == ImportPlatform.TRAKT)
                    || (format == ImportSourceFormat.LETTERBOXD_CSV && platform == ImportPlatform.LETTERBOXD);
            case PICKS_TEMPLATE -> format == ImportSourceFormat.WATCHWISE_TEMPLATE_JSON && platform == null;
        };
    }
}
