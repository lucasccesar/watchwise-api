package com.watchwise.watchwise_api.diaryentry.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DiaryViewGroupedItemDTO(
        String kind,
        ContentRefDTO content,
        UUID ratingContentId,
        String backdropPath,
        ContentType groupType,
        int seasonCount,
        List<Integer> seasonNumbers,
        int episodeCount,
        Long totalRuntimeMinutes,
        LocalDate groupDate,
        Integer watchNumber,
        Instant orderedAt) implements DiaryViewResponseItemDTO {
    public DiaryViewGroupedItemDTO {
        kind = "GROUPED";
        seasonNumbers = List.copyOf(seasonNumbers);
    }
}
