package com.watchwise.watchwise_api.common.validation;

public final class TmdbPosterUrlPolicy {

    public static final String PREFIX = "https://image.tmdb.org/t/p/w342/";

    private TmdbPosterUrlPolicy() {
    }

    public static boolean isValid(String value) {
        return value != null && value.startsWith(PREFIX) && value.length() > PREFIX.length();
    }
}
