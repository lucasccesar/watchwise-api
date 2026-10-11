package com.watchwise.watchwise_api.common.search;

import com.watchwise.watchwise_api.common.exception.BadRequestException;

public final class SearchTextNormalizer {
    private SearchTextNormalizer() { }

    public static String escapeLikeWildcards(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public static String normalizeOptionalQuery(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() > 100) throw new BadRequestException("Query must be at most 100 characters");
        return escapeLikeWildcards(trimmed);
    }
}
