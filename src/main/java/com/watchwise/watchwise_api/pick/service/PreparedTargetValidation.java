package com.watchwise.watchwise_api.pick.service;

import java.time.LocalDate;

public record PreparedTargetValidation(ResolvedPickTarget.TargetKey key, boolean exists, LocalDate releaseDate, boolean checked) {
    public PreparedTargetValidation(ResolvedPickTarget.TargetKey key, boolean exists, LocalDate releaseDate) {
        this(key, exists, releaseDate, true);
    }
}
