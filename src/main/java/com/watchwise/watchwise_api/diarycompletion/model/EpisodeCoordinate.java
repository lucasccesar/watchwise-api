package com.watchwise.watchwise_api.diarycompletion.model;

public record EpisodeCoordinate(int seasonNumber, int episodeNumber) {

    public EpisodeCoordinate {
        if (seasonNumber <= 0 || episodeNumber <= 0) {
            throw new IllegalArgumentException("Episode coordinates require positive season and episode numbers");
        }
    }
}
