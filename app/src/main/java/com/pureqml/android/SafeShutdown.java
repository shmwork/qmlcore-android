package com.pureqml.android;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Helper to reliably shutdown ExecutorService to avoid leaking background tasks.
 */
public final class SafeShutdown {
    private SafeShutdown() {}

    public static void shutdownAndAwait(ExecutorService ex, long timeoutSeconds) {
        if (ex == null) return;
        try {
            ex.shutdown();
            if (!ex.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                ex.shutdownNow();
                ex.awaitTermination(Math.max(1, timeoutSeconds / 2), TimeUnit.SECONDS);
            }
        } catch (InterruptedException ie) {
            try { ex.shutdownNow(); } catch (Throwable ignored) {}
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            try { ex.shutdownNow(); } catch (Throwable ignored) {}
        }
    }
}
