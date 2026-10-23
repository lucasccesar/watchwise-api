package com.watchwise.watchwise_api.notification.entity;

import lombok.Getter;

@Getter
public enum NotificationTargetType {
    COMMENT("comment"),
    DIARY_ENTRY("review"),
    CONTENT_REVIEW("review"),
    DROPPED_ENTRY("review"),
    USER_LIST("list"),
    PICK("Pick"),
    PICKS_TEMPLATE("template"),
    REPORT_CASE("report");

    private final String displayLabel;

    NotificationTargetType(String displayLabel) {
        this.displayLabel = displayLabel;
    }
}
