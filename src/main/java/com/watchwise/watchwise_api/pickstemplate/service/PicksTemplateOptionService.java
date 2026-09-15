package com.watchwise.watchwise_api.pickstemplate.service;

import com.watchwise.watchwise_api.pick.dto.PickOptionSearchDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateOptionCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateOptionDTO;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface PicksTemplateOptionService {
    PicksTemplateOptionDTO addOption(UUID actorId, UUID templateId, UUID categoryId, PicksTemplateOptionCreationDTO dto);
    Page<PickOptionSearchDTO> searchOptions(UUID viewerId, UUID templateId, UUID categoryId, String query,
                                             String seriesTmdbId, Integer seasonNumber, Integer page, Integer size);
    void deleteOption(UUID actorId, UUID templateId, UUID categoryId, UUID optionId);
}
