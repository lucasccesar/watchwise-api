package com.watchwise.watchwise_api.dailygame.entity;

public enum DailyGameType {
    MOVIE_BY_POSTER(6, DailyGameTargetKind.MOVIE),
    SERIES_BY_POSTER(6, DailyGameTargetKind.SERIES),
    PERSON_BY_FACE(6, DailyGameTargetKind.PERSON),
    EPISODE_BY_FRAME(10, DailyGameTargetKind.EPISODE),
    MOVIE_BY_INFO(10, DailyGameTargetKind.MOVIE),
    SERIES_BY_INFO(10, DailyGameTargetKind.SERIES),
    ACTOR_BY_MOVIE_FILMOGRAPHY(10, DailyGameTargetKind.PERSON),
    ACTOR_BY_SERIES_FILMOGRAPHY(10, DailyGameTargetKind.PERSON);

    private final int maxAttempts;
    private final DailyGameTargetKind targetKind;

    DailyGameType(int maxAttempts, DailyGameTargetKind targetKind) {
        this.maxAttempts = maxAttempts;
        this.targetKind = targetKind;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public DailyGameTargetKind targetKind() {
        return targetKind;
    }
}
