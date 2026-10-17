package com.watchwise.watchwise_api.common.tmdb;

import java.util.Locale;

public record TmdbSearchCacheKey(String query, TmdbSearchType type, String language, String region, int page) {

    public static TmdbSearchCacheKey of(String query, TmdbSearchType type, String language, int page) {
        return of(query, type, language, null, page);
    }

    public static TmdbSearchCacheKey of(
            String query, TmdbSearchType type, String language, String region, int page) {
        return new TmdbSearchCacheKey(query.trim().toLowerCase(Locale.ROOT), type, language, region, page);
    }
}
