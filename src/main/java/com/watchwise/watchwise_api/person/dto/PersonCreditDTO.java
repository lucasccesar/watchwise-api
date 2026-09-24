package com.watchwise.watchwise_api.person.dto;

import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.person.entity.PersonParticipation;

import java.util.List;

public record PersonCreditDTO(
        String tmdbId,
        MovieOrSeriesType type,
        String title,
        String posterUrl,
        String releaseDate,
        List<String> characters,
        List<String> jobs,
        PersonParticipation participation,
        boolean isWatched,
        boolean isInList) {
}
