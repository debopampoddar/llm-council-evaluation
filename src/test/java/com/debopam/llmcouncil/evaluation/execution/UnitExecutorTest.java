package com.debopam.llmcouncil.evaluation.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link UnitExecutor}.
 *
 * <p>The concurrency tests are written so they cannot pass on a sequential
 * implementation. A test that merely asserts "all units ran" is satisfied by a
 * for-loop, which is exactly the thing being replaced — so the overlap test
 * measures peak simultaneous occupancy and the barrier test would deadlock and
 * time out rather than pass if units were executed one at a time.
 */
class UnitExecutorTest {

    @Test
    @DisplayName("the default is sequential, and sequential means in order on the calling thread")
    void defaultsToSequentialExecution() {
        assertEquals(1, new UnitExecutor(1).concurrency());
        List<Integer> order = new ArrayList<>();
        Thread caller = Thread.currentThread();
        List<Runnable> units = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int value = i;
            units.add(() -> {
                assertSame(caller, Thread.currentThread(), "sequential units must not leave the calling thread");
                order.add(value);
            });
        }
        new UnitExecutor(1).run(units);
        assertEquals(List.of(0, 1, 2, 3, 4), order);
    }

    @Test
    @DisplayName("units genuinely overlap: peak occupancy exceeds one")
    void unitsActuallyRunConcurrently() throws Exception {
        int width = 4;
        CountDownLatch allStarted = new CountDownLatch(width);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Runnable> units = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            units.add(() -> {
                int now = inFlight.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                allStarted.countDown();
                try {
                    // Every unit waits for every other to arrive. A sequential
                    // implementation can never satisfy this, so it times out.
                    assertTrue(allStarted.await(5, TimeUnit.SECONDS),
                            "units did not overlap; execution was serialised");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    fail("interrupted");
                }
                inFlight.decrementAndGet();
            });
        }
        new UnitExecutor(width).run(units);
        assertEquals(width, peak.get(), "expected all units in flight at once");
        assertEquals(0, inFlight.get());
    }

    @Test
    @DisplayName("concurrency is bounded by the configured width")
    void neverExceedsTheConfiguredWidth() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Runnable> units = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            units.add(() -> {
                peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try { Thread.sleep(15); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                inFlight.decrementAndGet();
            });
        }
        new UnitExecutor(3).run(units);
        assertTrue(peak.get() > 1, "positive control: the units must have overlapped at all");
        assertTrue(peak.get() <= 3, "peak occupancy " + peak.get() + " exceeded the configured width of 3");
    }

    @Test
    @DisplayName("every unit runs exactly once under concurrency")
    void runsEveryUnitExactlyOnce() {
        List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
        List<Runnable> units = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int value = i;
            units.add(() -> seen.add(value));
        }
        new UnitExecutor(4).run(units);
        assertEquals(50, seen.size());
        assertEquals(50, Set.copyOf(seen).size(), "a unit ran twice or was skipped");
    }

    @Test
    @DisplayName("the first failure propagates to the caller")
    void propagatesTheFirstFailure() {
        List<Runnable> units = new ArrayList<>();
        units.add(() -> { throw new IllegalStateException("budget exhausted"); });
        for (int i = 0; i < 6; i++) {
            units.add(() -> { try { Thread.sleep(5); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } });
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new UnitExecutor(3).run(units));
        assertEquals("budget exhausted", ex.getMessage(), "the original exception must survive, not be wrapped");
    }

    @Test
    @DisplayName("a failure in a sequential run still propagates")
    void propagatesFailureWhenSequential() {
        assertThrows(IllegalStateException.class, () -> new UnitExecutor(1)
                .run(List.of(() -> { throw new IllegalStateException("nope"); })));
    }

    @Test
    @DisplayName("an empty or single-unit batch is handled without a pool")
    void handlesTrivialBatches() {
        assertDoesNotThrow(() -> new UnitExecutor(4).run(List.of()));
        AtomicInteger ran = new AtomicInteger();
        new UnitExecutor(4).run(List.of(ran::incrementAndGet));
        assertEquals(1, ran.get());
    }

    @Test
    @DisplayName("configuration is clamped, and a malformed value falls back to sequential")
    void clampsAndFallsBack() {
        assertEquals(1, new UnitExecutor(0).concurrency());
        assertEquals(1, new UnitExecutor(-4).concurrency());
        assertEquals(UnitExecutor.MAXIMUM, new UnitExecutor(999).concurrency());
        String previous = System.getProperty(UnitExecutor.PROPERTY);
        try {
            System.setProperty(UnitExecutor.PROPERTY, "3");
            assertEquals(3, UnitExecutor.configuredConcurrency());
            System.setProperty(UnitExecutor.PROPERTY, "not-a-number");
            assertEquals(1, UnitExecutor.configuredConcurrency(),
                    "a malformed speed knob must not stop an otherwise valid experiment");
            System.setProperty(UnitExecutor.PROPERTY, "99");
            assertEquals(UnitExecutor.MAXIMUM, UnitExecutor.configuredConcurrency());
        } finally {
            if (previous == null) System.clearProperty(UnitExecutor.PROPERTY);
            else System.setProperty(UnitExecutor.PROPERTY, previous);
        }
    }
}
