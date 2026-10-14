package com.watchwise.watchwise_api.dailygame.service;

import com.watchwise.watchwise_api.dailygame.dto.DailyGameRankingViewDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;

import java.util.UUID;

public interface DailyGameRankingViewService {

    DailyGameRankingViewDTO getRankingView(
            UUID viewerId,
            DailyGameType gameType,
            Integer page,
            Integer size);
}
