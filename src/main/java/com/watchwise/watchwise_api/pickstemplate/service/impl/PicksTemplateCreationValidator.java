package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCategoryCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCreationDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class PicksTemplateCreationValidator {
    private final Validator validator;

    public PicksTemplateCreationValidator(Validator validator) {
        this.validator = validator;
    }

    public void validate(PicksTemplateCreationDTO dto) {
        if (dto == null) throw new BadRequestException("Template creation body is required");
        Set<ConstraintViolation<PicksTemplateCreationDTO>> violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            String message = violations.stream().map(ConstraintViolation::getMessage)
                    .sorted().findFirst().orElse("Template creation is invalid");
            throw new BadRequestException(message);
        }
        for (PicksTemplateCategoryCreationDTO category : dto.categories()) {
            if (category == null || category.allowedType() == null || category.optionMode() == null
                    || category.group() == null) {
                throw new BadRequestException("Category configuration is incomplete");
            }
            PicksTemplateCategoryOptionsValidator.validateNewCategory(category.optionMode(), category.options());
        }
    }
}
