package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the bounded-async {@code updateAsync(source, fn, executor)} overload, plus a check that
 * sibling access is served by closing over the source (the idiom that replaced the dropped {@code
 * update*With} family).
 */
class BoundedAsyncTest {

  record User(String name, String bio) {}

  record Team(String name, List<User> users) {}

  static final Telescope<Team, User> USERS = Telescope.of(Team.class).each(Team::users);

  @Nested
  @DisplayName("sibling access via closure")
  class SiblingViaClosure {

    @Test
    @DisplayName("the lambda reads a sibling off the source by closing over it")
    void closesOverRoot() {
      final var team = new Team("eng", List.of(new User("alice", ""), new User("bob", "")));

      // No updateWith needed — `team` is already in scope, so the lambda just reads team.name().
      final var updated = USERS.update(team, user -> new User(user.name(), "Member of " + team.name()));

      assertEquals("Member of eng", updated.users().get(0).bio());
      assertEquals("Member of eng", updated.users().get(1).bio());
    }
  }

  @Nested
  @DisplayName("updateAsync(source, fn, executor) — bounded concurrency")
  class BoundedAsync {

    @Test
    @DisplayName("at most N fn invocations are in flight at once when N=2")
    void boundsConcurrency() throws Exception {
      final var inFlight = new AtomicInteger();
      final var maxObserved = new AtomicInteger();
      final var team = new Team(
        "x",
        List.of(
          new User("a", ""),
          new User("b", ""),
          new User("c", ""),
          new User("d", ""),
          new User("e", ""),
          new User("f", "")
        )
      );

      final var pool = Executors.newFixedThreadPool(2);
      try {
        final var done = USERS.updateAsync(
          team,
          user -> {
            final int now = inFlight.incrementAndGet();
            maxObserved.updateAndGet(prev -> Math.max(prev, now));
            try {
              Thread.sleep(50); // hold the slot
            } catch (final InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return CompletableFuture.completedFuture(new User(user.name(), "done"));
          },
          pool
        );

        final var result = done.get(15, TimeUnit.SECONDS);
        assertEquals(6, result.users().size());
        assertTrue(maxObserved.get() <= 2, "max concurrent invocations should be ≤ 2, was " + maxObserved.get());
        assertTrue(maxObserved.get() >= 1, "must have at least 1 in-flight at peak");
      } finally {
        pool.shutdown();
      }
    }

    @Test
    @DisplayName("rebuilt batch is correct under the executor")
    void rebuilds() throws Exception {
      final var team = new Team("eng", List.of(new User("alice", ""), new User("bob", "")));

      final var pool = Executors.newFixedThreadPool(2);
      try {
        final var done = USERS.updateAsync(
          team,
          user -> CompletableFuture.completedFuture(new User(user.name(), "x")),
          pool
        );
        final var result = done.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("x", "x"), result.users().stream().map(User::bio).toList());
      } finally {
        pool.shutdown();
      }
    }
  }
}
