package com.watchwise.watchwise_api.pick.service;

import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateOption;

import java.util.UUID;

public interface PickTargetService {

    ResolvedPickTarget validateForCategory(
            UUID viewerId,
            PicksTemplate template,
            PicksTemplateCategory category,
            PickTargetDTO target);

    ResolvedPickTarget validateFixedOption(
            UUID viewerId,
            PicksTemplate template,
            PicksTemplateCategory category,
            PickTargetDTO target);

    boolean matches(PicksTemplateOption option, ResolvedPickTarget target);

    boolean isValid(
            UUID viewerId,
            PicksTemplate template,
            PicksTemplateCategory category,
            PickSelection selection);
}
