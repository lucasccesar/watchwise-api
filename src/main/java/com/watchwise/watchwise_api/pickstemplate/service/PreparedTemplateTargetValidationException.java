package com.watchwise.watchwise_api.pickstemplate.service;

import com.watchwise.watchwise_api.common.exception.BadRequestException;

public class PreparedTemplateTargetValidationException extends BadRequestException {
    private final TemplateOptionPosition position;

    public PreparedTemplateTargetValidationException(TemplateOptionPosition position) {
        super("The fixed nominee does not exist in TMDB or falls outside the template eligibility dates");
        this.position = java.util.Objects.requireNonNull(position);
    }

    public TemplateOptionPosition position() { return position; }
}
