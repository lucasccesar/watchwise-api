package com.watchwise.watchwise_api.trending.service;

public enum TrendingTimeWindow {
    DAY("day"),
    WEEK("week");

    private final String value;

    TrendingTimeWindow(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
