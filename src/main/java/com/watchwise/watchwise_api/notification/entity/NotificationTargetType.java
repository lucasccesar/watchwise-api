package com.watchwise.watchwise_api.notification.entity;

import lombok.Getter;

@Getter
public enum NotificationTargetType {
    COMMENT("comment"),
    DIARY_ENTRY("review"),
    DROPPED_ENTRY("review"),
    USER_LIST("list"),
    PICK("Pick"),
    PICKS_TEMPLATE("template");

    private final String displayLabel;

    NotificationTargetType(String displayLabel) {
        this.displayLabel = displayLabel;
    }
}
