package com.watchwise.watchwise_api.common.transaction;

@FunctionalInterface
public interface AdvisoryLock {

    void lock(String identity);
}
