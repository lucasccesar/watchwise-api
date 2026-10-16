package com.watchwise.watchwise_api.person.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardStatsDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.person.entity.PersonParticipation;

import java.time.LocalDate;
import java.util.List;

public record PersonCreditViewDTO(
        String tmdbId,
        MovieOrSeriesType type,
        String title,
        String posterUrl,
        LocalDate releaseDate,
        List<String> characters,
        List<String> jobs,
        PersonParticipation participation,
        Integer creditedEpisodeCount,
        Double popularity,
        ContentCardStatsDTO stats,
        ContentCardViewerStateDTO viewerState,
        boolean isInAnyList,
        ContentPreviewStatus previewStatus) {

    public PersonCreditViewDTO {
        characters = List.copyOf(characters);
        jobs = List.copyOf(jobs);
    }
}
