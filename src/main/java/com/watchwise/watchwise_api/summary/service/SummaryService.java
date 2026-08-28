package com.watchwise.watchwise_api.summary.service;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.summary.dto.SummaryResponseDTO;

import java.util.UUID;

public interface SummaryService {

    SummaryResponseDTO getSummary(UUID viewerId, UUID userId, ContentType type);

}
