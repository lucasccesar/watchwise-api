package com.watchwise.watchwise_api.dailygame.dto;

import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;

import java.util.List;

public record DailyGameRankingViewDTO(
        List<DailyGameRankingViewEntryDTO> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        DailyGameType gameType,
        DailyGameRankingViewerDTO viewer) {
}
