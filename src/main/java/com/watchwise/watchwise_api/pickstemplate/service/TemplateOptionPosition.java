package com.watchwise.watchwise_api.pickstemplate.service;

public record TemplateOptionPosition(int categoryIndex, int optionIndex) {
    public TemplateOptionPosition {
        if (categoryIndex < 0 || optionIndex < 0) {
            throw new IllegalArgumentException("Template option position cannot be negative");
        }
    }
}
