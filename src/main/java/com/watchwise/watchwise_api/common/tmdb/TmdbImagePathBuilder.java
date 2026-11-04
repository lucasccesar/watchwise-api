package com.watchwise.watchwise_api.common.tmdb;

import java.net.URI;

public final class TmdbImagePathBuilder {

    private static final String TMDB_IMAGE_HOST = "image.tmdb.org";
    private static final String TMDB_IMAGE_PATH_PREFIX = "/t/p/";

    private TmdbImagePathBuilder() {
    }

    public static String posterPath(String imagePath) {
        return imagePath(imagePath);
    }

    public static String backdropPath(String imagePath) {
        return imagePath(imagePath);
    }

    public static String profilePath(String imagePath) {
        return imagePath(imagePath);
    }

    public static String stillPath(String imagePath) {
        return imagePath(imagePath);
    }

    public static String imagePath(String imagePath) {
        if (imagePath == null || imagePath.isBlank()) {
            return null;
        }
        String value = imagePath.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            value = rawPathFromTmdbUrl(value);
            if (value == null) {
                return null;
            }
        }
        String normalizedPath = value.replaceFirst("^/+", "");
        return normalizedPath.isBlank() ? null : "/" + normalizedPath;
    }

    private static String rawPathFromTmdbUrl(String imageUrl) {
        try {
            URI uri = URI.create(imageUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !TMDB_IMAGE_HOST.equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != -1
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                return null;
            }
            String path = uri.getRawPath();
            if (path == null || !path.startsWith(TMDB_IMAGE_PATH_PREFIX)) {
                return null;
            }
            String sizedPath = path.substring(TMDB_IMAGE_PATH_PREFIX.length());
            int separator = sizedPath.indexOf('/');
            return separator <= 0 || separator == sizedPath.length() - 1
                    ? null
                    : sizedPath.substring(separator + 1);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
