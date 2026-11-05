package com.watchwise.watchwise_api.common.concurrent;

public interface ShutdownAwareRunnable extends Runnable {

    @Override
    void run();

    boolean hasStarted();

    void onShutdownRejection();
}
