package com.watchwise.watchwise_api.platformimport.storage;

import software.amazon.awssdk.core.ResponseInputStream;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class DeadlineInputStream extends FilterInputStream {
    private final ResponseInputStream<?> response;
    private final long deadlineNanos;
    private final ScheduledFuture<?> abortTask;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean expired = new AtomicBoolean();

    DeadlineInputStream(ResponseInputStream<?> response, long deadlineNanos,
                        ScheduledExecutorService scheduler) {
        super(response);
        this.response = response;
        this.deadlineNanos = deadlineNanos;
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            expired.set(true);
            response.abort();
            abortTask = null;
        } else {
            abortTask = scheduler.schedule(() -> {
                expired.set(true);
                response.abort();
            }, remaining, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public int read() throws IOException {
        checkDeadline();
        try {
            int value = super.read();
            checkDeadline();
            return value;
        } catch (IOException exception) {
            throw translate(exception);
        }
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        checkDeadline();
        try {
            int read = super.read(bytes, offset, length);
            checkDeadline();
            return read;
        } catch (IOException exception) {
            throw translate(exception);
        }
    }

    @Override
    public long skip(long count) throws IOException {
        checkDeadline();
        try {
            long skipped = super.skip(count);
            checkDeadline();
            return skipped;
        } catch (IOException exception) {
            throw translate(exception);
        }
    }

    @Override
    public int available() throws IOException {
        checkDeadline();
        try {
            return super.available();
        } catch (IOException exception) {
            throw translate(exception);
        }
    }

    private void checkDeadline() {
        if (closed.get()) throw new ImportStorageUnavailableException(
                new IOException("Import artifact stream is closed"));
        if (expired.get() || deadlineNanos - System.nanoTime() <= 0) {
            expired.set(true);
            response.abort();
            throw new ImportStorageUnavailableException(
                    new IOException("Import artifact read deadline exceeded"));
        }
    }

    private ImportStorageUnavailableException translate(IOException failure) {
        if (expired.get() || deadlineNanos - System.nanoTime() <= 0) {
            expired.set(true);
            return new ImportStorageUnavailableException(
                    new IOException("Import artifact read deadline exceeded", failure));
        }
        return new ImportStorageUnavailableException(failure);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (abortTask != null) abortTask.cancel(false);
        response.abort();
    }
}
