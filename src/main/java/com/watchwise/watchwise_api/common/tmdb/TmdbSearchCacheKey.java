package com.watchwise.watchwise_api.common.tmdb;

import java.util.Locale;

public record TmdbSearchCacheKey(String query, TmdbSearchType type, String language, int page) {

    public static TmdbSearchCacheKey of(String query, TmdbSearchType type, String language, int page) {
        return new TmdbSearchCacheKey(query.trim().toLowerCase(Locale.ROOT), type, language, page);
    }
}
