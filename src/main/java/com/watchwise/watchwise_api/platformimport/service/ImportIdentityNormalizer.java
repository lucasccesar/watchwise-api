package com.watchwise.watchwise_api.platformimport.service;

import java.text.Normalizer;

public class ImportIdentityNormalizer {
    public String normalizeTitle(String title) {
        if (title == null) return null;
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFC).strip();
        return normalized.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    public String canonicalSourceId(String sourceId) {
        return sourceId == null ? null : sourceId.strip();
    }
}
