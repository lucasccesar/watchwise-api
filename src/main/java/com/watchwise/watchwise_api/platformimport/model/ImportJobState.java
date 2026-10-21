package com.watchwise.watchwise_api.platformimport.model;

public enum ImportJobState {
    ANALYZING, AWAITING_SELECTION, RESOLVING, READY, IMPORTING,
    COMPLETED, COMPLETED_WITH_WARNINGS, FAILED, CANCELLED, EXPIRED
}
