package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins what the {@code updateAsync(source, fn, executor)} executor actually bounds: concurrent
 * {@code fn} invocations — NOT operations in flight behind futures {@code fn} returns. Both truths
 * are pinned so the documentation cannot drift back to claiming an in-flight bound: a non-blocking
 * {@code fn} releases its pool thread the moment it returns a future, so every element's operation
 * can be outstanding at once.
 */
class EffectsConcurrencyTest {

  record Box(List<String> items) {}

  static final Box TEN = new Box(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"));
  static final Telescope<Box, String> ITEMS = Telescope.of(Box.class).each(Box::items);

  @Nested
  @DisplayName("the executor bounds concurrent fn invocations")
  class InvocationBound {

    @Test
    @DisplayName("blocking work inside fn never exceeds the pool size")
    void blockingWorkInsideFnIsBounded() throws Exception {
      final var inFn = new AtomicInteger();
      final var maxInFn = new AtomicInteger();
      try (var pool = Executors.newFixedThreadPool(2)) {
        final var result = ITEMS.updateAsync(
          TEN,
          s -> {
            final var now = inFn.incrementAndGet();
            maxInFn.accumulateAndGet(now, Math::max);
            try {
              Thread.sleep(20); // the blocking work happens INSIDE fn — the pool is the bound
            } catch (final InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            inFn.decrementAndGet();
            return CompletableFuture.completedFuture(s.toUpperCase());
          },
          pool
        );
        final var updated = result.get(10, TimeUnit.SECONDS);
        assertEquals("A", updated.items().get(0));
      }
      assertTrue(maxInFn.get() <= 2, "expected <= 2 concurrent fn invocations, saw " + maxInFn.get());
    }
  }

  @Nested
  @DisplayName("the executor does NOT bound operations in flight behind returned futures")
  class NoInFlightBound {

    @Test
    @DisplayName("a non-blocking fn puts every element's operation in flight at once")
    void nonBlockingFnIsNotBoundedInFlight() throws Exception {
      final var outstanding = new AtomicInteger();
      final var maxOutstanding = new AtomicInteger();
      final var allStarted = new CountDownLatch(10);
      try (var pool = Executors.newFixedThreadPool(2)) {
        final var result = ITEMS.updateAsync(
          TEN,
          s -> {
            // Non-blocking fn: start the "operation", return its future immediately. The pool
            // thread is released as soon as the future is returned — nothing holds a permit
            // through completion.
            final var now = outstanding.incrementAndGet();
            maxOutstanding.accumulateAndGet(now, Math::max);
            final var op = new CompletableFuture<String>();
            allStarted.countDown();
            CompletableFuture.runAsync(() -> {
              try {
                allStarted.await(); // completes only after every operation has started
              } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              outstanding.decrementAndGet();
              op.complete(s.toUpperCase());
            });
            return op;
          },
          pool
        );
        final var updated = result.get(10, TimeUnit.SECONDS);
        assertEquals(10, updated.items().size());
      }
      // All 10 operations were outstanding simultaneously despite the 2-thread pool: the pool
      // bounds fn invocation, not in-flight work. This is the documented contract — if a real
      // in-flight limiter is ever added, this pin tells you to update the docs.
      assertEquals(10, maxOutstanding.get());
    }
  }
}
