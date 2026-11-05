package com.watchwise.watchwise_api.common.validation;

public final class TmdbPosterPathPolicy {

    public static final String PATH_PREFIX = "/";

    private TmdbPosterPathPolicy() {
    }

    public static boolean isValid(String value) {
        if (value == null || value.length() <= 1 || value.length() > 2048
                || !value.startsWith(PATH_PREFIX)
                || value.startsWith("//")
                || value.codePoints().anyMatch(codePoint -> Character.isWhitespace(codePoint)
                        || Character.isSpaceChar(codePoint)
                        || Character.isISOControl(codePoint))
                || value.indexOf('?') >= 0
                || value.indexOf('#') >= 0
                || value.indexOf('\\') >= 0) {
            return false;
        }

        String[] segments = value.substring(1).split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }

        return true;
    }
}
