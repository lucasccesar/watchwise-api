package com.watchwise.watchwise_api.dailygame.service;

import com.watchwise.watchwise_api.dailygame.dto.DailyGameMediaRefDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameMediaResponseDTO;
import com.watchwise.watchwise_api.dailygame.media.DailyGameImageVariant;
import com.watchwise.watchwise_api.dailygame.media.DailyGameMediaSlot;
import java.util.UUID;

public interface DailyGameMediaService {
    DailyGameMediaRefDTO issue(UUID userId, UUID challengeId, DailyGameMediaSlot slot,
                               DailyGameImageVariant variant, String storedImagePath);
    DailyGameMediaResponseDTO fetch(UUID userId, String grantToken);
}
