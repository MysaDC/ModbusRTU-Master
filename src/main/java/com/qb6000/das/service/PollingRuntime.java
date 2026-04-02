package com.qb6000.das.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class PollingRuntime implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(PollingRuntime.class);

    private final ScheduledExecutorService scheduler;
    private final ExecutorService pollingExecutor;

    public PollingRuntime(int controllerCount) {
        int schedulerThreads = Math.max(1, Math.min(controllerCount, Runtime.getRuntime().availableProcessors()));
        this.scheduler = Executors.newScheduledThreadPool(
            schedulerThreads,
            Thread.ofPlatform().name("poll-scheduler-", 0).daemon(false).factory()
        );
        this.pollingExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("poll-worker-", 0).factory()
        );
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration interval) {
        return scheduler.scheduleWithFixedDelay(task, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void execute(Runnable task) {
        pollingExecutor.execute(task);
    }

    @Override
    public void close() {
        shutdown("轮询调度器", scheduler, 5);
        shutdown("轮询工作线程", pollingExecutor, 5);
    }

    private static void shutdown(String component, ExecutorService executorService, long timeoutSeconds) {
        executorService.shutdownNow();
        try {
            if (!executorService.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("{} 未在 {} 秒内停止", component, timeoutSeconds);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
