package com.example.bank;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class Concurrency {

    public interface Task {
        void run(int i) throws Exception;
    }

    private Concurrency() {
    }

    /** Starts all threads at the same instant via a latch to maximise contention. */
    public static void runConcurrently(int threads, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            futures.add(pool.submit((Callable<Void>) () -> {
                start.await();
                task.run(n);
                return null;
            }));
        }
        start.countDown();
        try {
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS); // rethrows unexpected failures
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
