package com.watchwise.watchwise_api.content.dto;

public record ContentStateDTO(
        WatchStatus watchStatus,
        ReleaseStatus releaseStatus,
        ContentProductionStatus productionStatus,
        Integer watchedEpisodeCount,
        Integer releasedEpisodeCount) {
}
