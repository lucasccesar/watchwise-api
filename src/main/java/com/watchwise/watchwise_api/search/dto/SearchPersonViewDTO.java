package com.watchwise.watchwise_api.search.dto;

public record SearchPersonViewDTO(
        String tmdbId,
        String name,
        String photoUrl,
        String knownForDepartment) {
}
