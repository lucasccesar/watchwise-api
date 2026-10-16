package com.watchwise.watchwise_api.person.service.impl;

import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.person.entity.PersonParticipation;

import java.time.LocalDate;
import java.util.List;

record PersonFilmographyCredit(MovieOrSeriesType type, String tmdbId, String title, String posterPath,
                               String backdropPath, LocalDate releaseDate, Double popularity,
                               Integer creditedEpisodeCount, List<String> characters, List<String> jobs,
                               PersonParticipation participation) {
    PersonFilmographyCredit {
        characters = List.copyOf(characters);
        jobs = List.copyOf(jobs);
    }
}
