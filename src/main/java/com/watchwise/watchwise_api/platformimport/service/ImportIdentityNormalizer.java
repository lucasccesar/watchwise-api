package com.watchwise.watchwise_api.platformimport.service;

import java.text.Normalizer;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

public class ImportIdentityNormalizer {
    private static final Pattern UNICODE_WHITESPACE = Pattern.compile("[\\p{IsWhite_Space}]+");

    public String normalizeTitle(String title) {
        if (title == null) return null;
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFC);
        return UNICODE_WHITESPACE.matcher(normalized).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }

    public String canonicalSourceId(String sourceId) {
        if (sourceId == null) return null;
        try {
            URI uri = new URI(sourceId);
            if (!uri.isAbsolute() || uri.getHost() == null) return sourceId;
            StringBuilder canonical = new StringBuilder()
                    .append(uri.getScheme().toLowerCase(Locale.ROOT)).append("://")
                    .append(uri.getHost().toLowerCase(Locale.ROOT));
            if (uri.getPort() != -1) canonical.append(':').append(uri.getPort());
            if (uri.getRawPath() != null) canonical.append(uri.getRawPath());
            if (uri.getRawQuery() != null) canonical.append('?').append(uri.getRawQuery());
            if (uri.getRawFragment() != null) canonical.append('#').append(uri.getRawFragment());
            return canonical.toString();
        } catch (URISyntaxException exception) {
            return sourceId;
        }
    }
}
