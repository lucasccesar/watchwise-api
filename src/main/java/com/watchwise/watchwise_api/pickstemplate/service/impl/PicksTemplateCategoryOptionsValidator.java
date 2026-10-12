package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;

import java.util.List;

final class PicksTemplateCategoryOptionsValidator {

    private PicksTemplateCategoryOptionsValidator() {
    }

    static void validateNewCategory(PickCategoryOptionMode optionMode, List<?> options) {
        int optionCount = options == null ? 0 : options.size();
        validateOptionCount(optionMode, optionCount);
    }

    static void validateModeChange(PickCategoryOptionMode currentMode, PickCategoryOptionMode newMode,
                                   int existingOptionCount) {
        if (currentMode != PickCategoryOptionMode.FIXED && newMode == PickCategoryOptionMode.FIXED) {
            validateOptionCount(newMode, existingOptionCount);
        }
    }

    private static void validateOptionCount(PickCategoryOptionMode optionMode, int optionCount) {
        if (optionMode == PickCategoryOptionMode.OPEN && optionCount > 0) {
            throw new BadRequestException("Open categories cannot receive fixed options");
        }
        if (optionMode == PickCategoryOptionMode.FIXED && optionCount == 0) {
            throw new BadRequestException("Fixed categories must have at least one option");
        }
    }
}
