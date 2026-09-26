package com.watchwise.watchwise_api.common.validation;

public final class TmdbPosterUrlPolicy {

    public static final String PREFIX = "https://image.tmdb.org/t/p/w342/";

    private TmdbPosterUrlPolicy() {
    }

    public static boolean isValid(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return false;
        }

        String suffix = value.substring(PREFIX.length());
        return !suffix.isEmpty()
                && !suffix.isBlank()
                && suffix.trim().equals(suffix)
                && suffix.chars().noneMatch(Character::isWhitespace);
    }
}
