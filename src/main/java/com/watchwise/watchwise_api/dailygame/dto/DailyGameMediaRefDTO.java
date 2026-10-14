package com.watchwise.watchwise_api.dailygame.dto;

import com.watchwise.watchwise_api.dailygame.media.DailyGameImageVariant;
import com.watchwise.watchwise_api.dailygame.media.DailyGameMediaSlot;

public record DailyGameMediaRefDTO(
        DailyGameMediaSlot slot, String url, DailyGameImageVariant variant, boolean revealed) {}
