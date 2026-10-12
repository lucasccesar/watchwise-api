package com.watchwise.watchwise_api.pick.service;

import com.watchwise.watchwise_api.pick.dto.PickCreationDTO;
import com.watchwise.watchwise_api.pick.dto.PickPatchDTO;
import com.watchwise.watchwise_api.pick.dto.PickPreviewDTO;
import com.watchwise.watchwise_api.pick.dto.PickResponseDTO;
import com.watchwise.watchwise_api.pick.dto.PickSort;
import com.watchwise.watchwise_api.pick.dto.PickDetailViewDTO;
import com.watchwise.watchwise_api.pick.dto.PickTemplatePickViewDTO;
import com.watchwise.watchwise_api.pick.dto.PickViewCardDTO;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface PickService {

    PickResponseDTO createPick(UUID userId, UUID templateId, PickCreationDTO dto);

    Page<PickPreviewDTO> getMyPicks(UUID userId, UUID templateId, Integer page, Integer size);

    Page<PickViewCardDTO> getMyPicksView(UUID viewerId, Integer page, Integer size);

    Page<PickTemplatePickViewDTO> getTemplatePicksView(
            UUID viewerId, UUID templateId, PickSort sort, Integer page, Integer size);

    Page<PickTemplatePickViewDTO> getMyPicksView(UUID viewerId, UUID templateId, Integer page, Integer size);

    PickDetailViewDTO getPickView(UUID viewerId, UUID pickId);

    Page<PickPreviewDTO> getTemplatePicks(UUID viewerId, UUID templateId, PickSort sort, Integer page, Integer size);

    PickResponseDTO getPick(UUID viewerId, UUID pickId);

    PickResponseDTO updatePick(UUID userId, UUID pickId, PickPatchDTO dto);

    void deletePick(UUID userId, UUID pickId);

    Page<PickPreviewDTO> getUserPicks(UUID viewerId, UUID ownerId, UUID templateId, Integer page, Integer size);
}
