package com.watchwise.watchwise_api.diaryentry.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import java.time.Instant;
import java.time.LocalDate;

public record DiaryViewGroupedItemDTO(
        String kind,
        ContentRefDTO content,
        ContentType groupType,
        int seasonCount,
        int episodeCount,
        Long totalRuntimeMinutes,
        LocalDate groupDate,
        Integer watchNumber,
        Instant orderedAt) implements DiaryViewResponseItemDTO {
    public DiaryViewGroupedItemDTO {
        kind = "GROUPED";
    }
}
