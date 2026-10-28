package com.watchwise.watchwise_api.pick.service;

/** Internal signal to release mutation locks and prepare a changed snapshot again. */
public class StalePickPreparationException extends RuntimeException {
}
