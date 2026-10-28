package com.watchwise.watchwise_api.pick.service;

import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public record PickPreparationRules(UUID templateId, LocalDate eligibilityStart, LocalDate eligibilityEnd,
        Map<UUID, CategoryRule> categories) {
    public PickPreparationRules {
        categories = Map.copyOf(categories);
    }

    public static PickPreparationRules capture(PicksTemplate template, Collection<PicksTemplateCategory> categories) {
        return new PickPreparationRules(template.getId(), template.getEligibilityStartDate(), template.getEligibilityEndDate(),
                categories.stream().map(category -> new CategoryRule(category.getId(), category.getPicksTemplate().getId(),
                                category.getAllowedType(), category.getOptionMode()))
                        .collect(Collectors.toMap(CategoryRule::id, Function.identity())));
    }

    public PicksTemplate template() {
        return PicksTemplate.builder().id(templateId).eligibilityStartDate(eligibilityStart).eligibilityEndDate(eligibilityEnd).build();
    }

    public PicksTemplateCategory category(UUID categoryId) {
        CategoryRule rule = categories.get(categoryId);
        return rule == null ? null : PicksTemplateCategory.builder().id(rule.id())
                .picksTemplate(PicksTemplate.builder().id(rule.templateId()).build())
                .allowedType(rule.allowedType()).optionMode(rule.optionMode()).build();
    }

    public record CategoryRule(UUID id, UUID templateId, PickAllowedType allowedType, PickCategoryOptionMode optionMode) {
    }
}
