package com.watchwise.watchwise_api.content.dto;

public record ContentPageWatchProviderDTO(
        Integer providerId,
        String providerName,
        String logoPath,
        String type,
        String watchUrl) {
}
