package com.watchwise.watchwise_api.pickstemplate.service;

import com.watchwise.watchwise_api.common.exception.NotFoundException;

public class PreparedTemplateTargetNotFoundException extends NotFoundException {
    private final TemplateOptionPosition position;

    public PreparedTemplateTargetNotFoundException(TemplateOptionPosition position, String message) {
        super(message);
        this.position = java.util.Objects.requireNonNull(position);
    }

    public TemplateOptionPosition position() { return position; }
}
