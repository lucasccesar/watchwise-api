package com.watchwise.watchwise_api.pick.service;

import com.watchwise.watchwise_api.pick.dto.PickCreationDTO;
import com.watchwise.watchwise_api.pick.dto.PickPatchDTO;
import com.watchwise.watchwise_api.pick.dto.PickResponseDTO;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface PickService {

    PickResponseDTO createPick(UUID userId, UUID templateId, PickCreationDTO dto);

    Page<PickResponseDTO> getMyPicks(UUID userId, UUID templateId, Integer page, Integer size);

    PickResponseDTO getPick(UUID viewerId, UUID pickId);

    PickResponseDTO updatePick(UUID userId, UUID pickId, PickPatchDTO dto);

    void deletePick(UUID userId, UUID pickId);

    Page<PickResponseDTO> getUserPicks(UUID viewerId, UUID ownerId, UUID templateId, Integer page, Integer size);
}
