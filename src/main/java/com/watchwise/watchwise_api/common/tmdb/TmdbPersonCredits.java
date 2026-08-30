package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbPersonCredits(List<TmdbCredit> cast, List<TmdbCredit> crew) {
}
