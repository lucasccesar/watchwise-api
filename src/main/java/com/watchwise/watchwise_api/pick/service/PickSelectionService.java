package com.watchwise.watchwise_api.pick.service;

import com.watchwise.watchwise_api.pick.dto.PickResponseDTO;
import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;

import java.util.UUID;

public interface PickSelectionService {

    PickResponseDTO upsertSelection(UUID userId, UUID pickId, UUID categoryId, PickTargetDTO target);

    void deleteSelection(UUID userId, UUID pickId, UUID categoryId);
}
