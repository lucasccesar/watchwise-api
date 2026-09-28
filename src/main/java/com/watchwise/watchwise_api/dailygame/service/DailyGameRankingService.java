package com.watchwise.watchwise_api.dailygame.service;

import com.watchwise.watchwise_api.dailygame.dto.DailyGameHistoryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameRankingEntryDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface DailyGameRankingService {

    Page<DailyGameHistoryDTO> getHistory(UUID userId, DailyGameType type, Integer page, Integer size);

    Page<DailyGameRankingEntryDTO> getGeneralRanking(Integer page, Integer size);

    Page<DailyGameRankingEntryDTO> getRanking(DailyGameType type, Integer page, Integer size);
}
