package com.aistudio.production.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Worker threads of the single JVM (default one, at most two). Each worker processes one request at a time. Lease
 * owners are unique per process start, so leases of a stopped process are only taken over after they expire.
 */
public class ProductionWorkerPool implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ProductionWorkerPool.class);

    private final ProductionExecutor executor;
    private final ExecutionProperties properties;
    private final boolean autoStartup;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    public ProductionWorkerPool(ProductionExecutor executor, ExecutionProperties properties, boolean autoStartup) {
        this.executor = executor;
        this.properties = properties;
        this.autoStartup = autoStartup;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        String instance = UUID.randomUUID().toString();
        for (int i = 1; i <= properties.workers(); i++) {
            String owner = "worker-" + i + ":" + instance;
            Thread thread = new Thread(() -> loop(owner), "production-worker-" + i);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
        log.info("Production executor started with {} worker(s), lease ceiling {}", properties.workers(),
                properties.maxLeases());
    }

    private void loop(String owner) {
        while (running && !Thread.currentThread().isInterrupted()) {
            boolean worked = false;
            try {
                worked = executor.runOnce(owner);
            } catch (RuntimeException e) {
                log.error("Production worker {} iteration failed", owner, e);
            }
            if (!worked && !pause()) {
                return;
            }
        }
    }

    private boolean pause() {
        try {
            Thread.sleep(properties.pollInterval().toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
        for (Thread thread : threads) {
            try {
                thread.join(properties.leaseDuration().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return autoStartup;
    }
}
