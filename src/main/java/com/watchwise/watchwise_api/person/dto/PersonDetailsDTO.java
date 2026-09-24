package com.watchwise.watchwise_api.person.dto;

import java.util.List;

public record PersonDetailsDTO(
        String tmdbId,
        String name,
        String biography,
        String birthday,
        String deathday,
        String placeOfBirth,
        String gender,
        String profileUrl,
        String knownForDepartment,
        List<String> alsoKnownAs,
        boolean isFollowing) {
}
