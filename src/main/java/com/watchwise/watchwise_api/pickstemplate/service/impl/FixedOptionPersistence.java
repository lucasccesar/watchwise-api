package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateOption;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateOptionRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

final class FixedOptionPersistence {

    private FixedOptionPersistence() {
    }

    static PicksTemplateOption saveAndFlush(PicksTemplateOptionRepository repository, PicksTemplateOption option) {
        try {
            return repository.saveAndFlush(option);
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "uq_picks_template_options_fixed_target".equals(violation.getConstraintName())) {
                    throw new ConflictException("This target is already a fixed option for this category");
                }
            }
            throw exception;
        }
    }
}
