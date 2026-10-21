package com.payflow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs N tasks at the same instant. Every thread waits at a starting gate (a CountDownLatch);
 * when all are ready the gate opens and they all fire together, which maximises the chance of a race.
 */
public final class Concurrently {

    private Concurrently() {
    }

    public static <T> List<T> run(int threads, Callable<T> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch gate = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    gate.await();          // wait until every thread is lined up
                    return task.call();
                }));
            }
            ready.await();
            gate.countDown();              // go!

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }
}
