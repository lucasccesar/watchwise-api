package com.watchwise.watchwise_api.pickstemplate.service;

import com.watchwise.watchwise_api.common.exception.ConflictException;

public class PreparedTemplateDuplicateTargetException extends ConflictException {
    private final TemplateOptionPosition position;

    public PreparedTemplateDuplicateTargetException(TemplateOptionPosition position) {
        super("This target is already a fixed option for this category");
        this.position = java.util.Objects.requireNonNull(position);
    }

    public TemplateOptionPosition position() { return position; }
}
