package com.watchwise.watchwise_api.pickstemplate.service;

import java.util.UUID;

public record PreparedTemplateOption(
        int categoryIndex,
        int optionIndex,
        UUID contentId,
        String personTmdbId,
        UUID contextContentId) {

    public PreparedTemplateOption {
        if (categoryIndex < 0 || optionIndex < 0
                || (contentId == null) == (personTmdbId == null)
                || personTmdbId != null && personTmdbId.isBlank()) {
            throw new IllegalArgumentException("Prepared template option is invalid");
        }
    }
}
