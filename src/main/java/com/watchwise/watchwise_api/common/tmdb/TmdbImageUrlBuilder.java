package com.watchwise.watchwise_api.common.tmdb;

public final class TmdbImageUrlBuilder {

    private static final String IMAGE_BASE_URL = "https://image.tmdb.org/t/p";
    private static final String POSTER_SIZE = "w500";
    private static final String PROFILE_SIZE = "w185";

    private TmdbImageUrlBuilder() {
    }

    public static String posterUrl(String imagePath) {
        return build(imagePath, POSTER_SIZE);
    }

    public static String profileUrl(String imagePath) {
        return build(imagePath, PROFILE_SIZE);
    }

    private static String build(String imagePath, String size) {
        if (imagePath == null || imagePath.isBlank()) {
            return null;
        }
        return IMAGE_BASE_URL + "/" + size + "/" + imagePath.trim().replaceFirst("^/+", "");
    }
}
