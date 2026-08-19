package com.debopam.llmcouncil.evaluation.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs independent evaluation units with bounded concurrency.
 *
 * <p>Concurrency is an environmental execution setting rather than a plan field,
 * so it does not change the experiment's run id. It is nevertheless recorded in
 * the run manifest: concurrency can change resource contention and latency, and a
 * resume must not silently mix execution conditions.
 *
 * <p>The default is {@code 1}, which executes units inline on the calling thread
 * and is byte-for-byte the previous sequential behaviour.
 *
 * <p><b>What this does not do.</b> It never runs council units concurrently.
 * {@code council.runtime.max-concurrent-runs} defaults to 1 in the service under
 * test, so a second overlapping council request is rejected rather than queued.
 * The caller decides which units are eligible; this class only bounds them.
 */
public final class UnitExecutor {

    /** System property consulted first. */
    public static final String PROPERTY = "evaluation.concurrency";
    /** Environment variable consulted when the system property is absent. */
    public static final String ENVIRONMENT_VARIABLE = "EVALUATION_CONCURRENCY";
    /** Upper bound. Beyond a handful of streams a single accelerator stops gaining. */
    public static final int MAXIMUM = 8;

    private final int concurrency;

    /**
     * @param concurrency simultaneous units; values below 2 run everything inline
     */
    public UnitExecutor(int concurrency) {
        this.concurrency = Math.min(Math.max(concurrency, 1), MAXIMUM);
    }

    /** @return an executor configured from the environment, defaulting to sequential */
    public static UnitExecutor fromEnvironment() {
        return new UnitExecutor(configuredConcurrency());
    }

    /**
     * Resolve the requested concurrency, preferring the system property.
     *
     * <p>An unparseable or non-positive value falls back to 1 rather than
     * failing: a malformed speed knob must not stop an experiment that is
     * otherwise valid.
     *
     * @return requested units in flight, clamped to {@code [1, MAXIMUM]}
     */
    public static int configuredConcurrency() {
        String raw = System.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) raw = System.getenv(ENVIRONMENT_VARIABLE);
        if (raw == null || raw.isBlank()) return 1;
        try {
            return Math.min(Math.max(Integer.parseInt(raw.trim()), 1), MAXIMUM);
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    /** @return the effective number of units this executor will keep in flight */
    public int concurrency() {
        return concurrency;
    }

    /**
     * Run every unit, returning once all started units have finished or one has
     * failed and the remaining queued units have been cancelled.
     *
     * <p>The first failure is rethrown and the remaining queued units are
     * cancelled. Cancellation is {@code cancel(false)}: a unit already in flight
     * is left to finish rather than interrupted, because interrupting a thread
     * mid-HTTP-call leaves the provider connection undefined and its evidence
     * half-written. That mirrors how the service under test handles its own
     * cancellation.
     *
     * @param units independent units of work; executed in order when sequential
     */
    public void run(List<Runnable> units) {
        if (units.isEmpty()) return;
        if (concurrency <= 1 || units.size() == 1) {
            units.forEach(Runnable::run);
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, units.size()));
        try {
            BlockingQueue<Future<TrackedRunnable>> completed = new LinkedBlockingQueue<>();
            ExecutorCompletionService<TrackedRunnable> completion =
                    new ExecutorCompletionService<>(pool, completed);
            List<SubmittedUnit> submitted = new ArrayList<>(units.size());
            for (Runnable unit : units) {
                TrackedRunnable tracked = new TrackedRunnable(unit);
                submitted.add(new SubmittedUnit(tracked, completion.submit(() -> {
                    tracked.run();
                    return tracked;
                })));
            }
            RuntimeException failure = null;
            for (int finished = 0; finished < submitted.size(); finished++) {
                try {
                    completion.take().get();
                } catch (CancellationException ignored) {
                    // Cancelled after an earlier failure; that first failure is what is reported.
                } catch (ExecutionException ex) {
                    if (failure == null) {
                        failure = ex.getCause() instanceof RuntimeException runtime
                                ? runtime
                                : new IllegalStateException(ex.getCause());
                        submitted.forEach(SubmittedUnit::cancelIfQueued);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    submitted.forEach(SubmittedUnit::cancelIfQueued);
                    throw new IllegalStateException("Interrupted while running evaluation units", ex);
                }
            }
            if (failure != null) throw failure;
        } finally {
            pool.shutdown();
            awaitTermination(pool);
        }
    }

    private void awaitTermination(ExecutorService pool) {
        boolean interrupted = false;
        while (!pool.isTerminated()) {
            try {
                pool.awaitTermination(1, TimeUnit.DAYS);
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private enum UnitState { QUEUED, RUNNING, COMPLETED, CANCELLED }

    /**
     * Makes the queued/running transition explicit so failure handling never
     * calls {@code Future.cancel(false)} on a task that has already started.
     */
    private static final class TrackedRunnable implements Runnable {
        private final Runnable delegate;
        private final AtomicReference<UnitState> state = new AtomicReference<>(UnitState.QUEUED);

        private TrackedRunnable(Runnable delegate) {
            this.delegate = delegate;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(UnitState.QUEUED, UnitState.RUNNING)) return;
            try {
                delegate.run();
            } finally {
                state.set(UnitState.COMPLETED);
            }
        }

        private boolean cancelIfQueued() {
            return state.compareAndSet(UnitState.QUEUED, UnitState.CANCELLED);
        }
    }

    private record SubmittedUnit(TrackedRunnable unit, Future<TrackedRunnable> future) {
        private void cancelIfQueued() {
            if (unit.cancelIfQueued()) future.cancel(false);
        }
    }
}
