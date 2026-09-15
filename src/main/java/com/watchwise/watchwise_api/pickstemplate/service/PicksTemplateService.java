package com.watchwise.watchwise_api.pickstemplate.service;

import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePatchDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateResponseDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface PicksTemplateService {
    PicksTemplateResponseDTO createTemplate(UUID actorId, PicksTemplateCreationDTO dto);
    Page<PicksTemplatePreviewDTO> listTemplates(UUID viewerId, PickOrigin origin, String name, Integer page, Integer size);
    PicksTemplateResponseDTO getTemplate(UUID viewerId, UUID templateId);
    PicksTemplateResponseDTO updateTemplate(UUID actorId, UUID templateId, PicksTemplatePatchDTO dto);
    void detachOrDeleteTemplate(UUID actorId, UUID templateId);
}
