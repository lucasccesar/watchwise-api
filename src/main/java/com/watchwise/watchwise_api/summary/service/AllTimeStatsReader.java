package com.watchwise.watchwise_api.summary.service;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.summary.dto.AllTimeEditionStatsDTO;

import java.util.UUID;

public interface AllTimeStatsReader {

    AllTimeEditionStatsDTO read(UUID userId, ContentType type);
}
