package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.*;

import io.github.eschizoid.telescope.effects.Either;
import io.github.eschizoid.telescope.effects.Validated;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class TelescopeObserveAsyncTest {

  record User(String email) {}

  record Team(List<User> users) {}

  static final Team TEAM = new Team(List.of(new User("A"), new User("B")));

  static final class Queued implements Executor {

    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    public void execute(Runnable task) {
      tasks.add(task);
    }

    void drain() {
      while (!tasks.isEmpty()) tasks.remove().run();
    }
  }

  static void unexpected(Object value, Throwable failure) {
    fail("Unexpected callback failure", failure);
  }

  @Test
  void deferredUpdatedValuesAndOrder() {
    final var queue = new Queued();
    final var seen = new ArrayList<String>();
    final var path = Telescope.of(Team.class)
      .observeAsync(
        team -> seen.add("team:" + team.users().get(0).email()),
        queue,
        TelescopeObserveAsyncTest::unexpected
      )
      .each(Team::users)
      .observeAsync(user -> seen.add(user.email()), queue, TelescopeObserveAsyncTest::unexpected)
      .field(User::email);
    assertTrue(queue.tasks.isEmpty());
    final var result = path.update(TEAM, String::toLowerCase);
    assertEquals("a", result.users().get(0).email());
    assertTrue(seen.isEmpty());
    queue.drain();
    assertEquals(List.of("a", "b", "team:a"), seen);
    seen.clear();
    assertEquals("A", path.find(TEAM).orElseThrow());
    assertEquals(2, queue.tasks.size());
    queue.drain();
    assertEquals(List.of("team:A", "A"), seen);
  }

  @Test
  void blockedCallbackDoesNotBlockTransformation() throws Exception {
    final var entered = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    final var worker = Executors.newSingleThreadExecutor();
    try (final var caller = Executors.newSingleThreadExecutor()) {
      final var path = Telescope.of(User.class)
        .observeAsync(
          user -> {
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          },
          worker,
          TelescopeObserveAsyncTest::unexpected
        )
        .field(User::email);
      try {
        final var result = caller.submit(() -> path.update(new User("A"), String::toLowerCase));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(new User("a"), result.get(5, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
    } finally {
      release.countDown();
      worker.close();
    }
  }

  @Test
  void failuresAndRejectionAreReportedOnce() {
    final var errors = new ArrayList<Throwable>();
    final var failure = new RejectedExecutionException("callback failure");
    final var path = Telescope.of(User.class).observeAsync(
      user -> {
        throw failure;
      },
      Runnable::run,
      (value, error) -> errors.add(error)
    );
    assertEquals(new User("A"), path.read(new User("A")));
    assertEquals(List.of(failure), errors);
    errors.clear();
    final Executor rejecting = task -> {
      throw failure;
    };
    Telescope.of(User.class)
      .observeAsync(user -> fail(), rejecting, (value, error) -> errors.add(error))
      .read(new User("A"));
    assertEquals(List.of(failure), errors);
    assertDoesNotThrow(() ->
      Telescope.of(User.class)
        .observeAsync(
          user -> {
            throw failure;
          },
          Runnable::run,
          (value, error) -> {
            throw new IllegalStateException("handler");
          }
        )
        .read(new User("A"))
    );
    assertThrows(AssertionError.class, () ->
      Telescope.of(User.class)
        .observeAsync(
          user -> {
            throw new AssertionError();
          },
          Runnable::run,
          TelescopeObserveAsyncTest::unexpected
        )
        .read(new User("A"))
    );
  }

  @Test
  void effectFailuresQueueNothingAndSuccessDoesNotAwaitCallbacks() {
    final var queue = new Queued();
    final var seen = new ArrayList<String>();
    final var path = Telescope.of(Team.class)
      .each(Team::users)
      .field(User::email)
      .observeAsync(seen::add, queue, TelescopeObserveAsyncTest::unexpected);
    path.updateEither(TEAM, value -> Either.left("bad"));
    path.updateOptional(TEAM, value -> Optional.empty());
    path.updateValidated(TEAM, value -> Validated.invalid("bad"));
    assertThrows(CompletionException.class, () ->
      path.updateAsync(TEAM, value -> CompletableFuture.failedFuture(new IllegalStateException())).join()
    );
    assertTrue(queue.tasks.isEmpty());
    path.updateEither(TEAM, value -> Either.right(value.toLowerCase()));
    path.updateOptional(TEAM, value -> Optional.of(value.toLowerCase()));
    path.updateValidated(TEAM, value -> Validated.valid(value.toLowerCase()));
    path.updateAsync(TEAM, value -> CompletableFuture.completedFuture(value.toLowerCase()), Runnable::run).join();
    assertTrue(seen.isEmpty());
    assertEquals(8, queue.tasks.size());
    queue.drain();
    assertEquals(List.of("a", "b", "a", "b", "a", "b", "a", "b"), seen);
  }

  @Test
  void diagnosticsAndEmptyDescendants() {
    final var queue = new Queued();
    final var path = Telescope.of(Team.class).observeAsync(team -> {}, queue, TelescopeObserveAsyncTest::unexpected);
    path.trace(TEAM);
    path.explain();
    assertTrue(path.find(null).isEmpty());
    assertThrows(java.util.NoSuchElementException.class, () -> path.read(null));
    assertTrue(queue.tasks.isEmpty());
    final var empty = new Team(List.of());
    final var emails = path.each(Team::users).field(User::email);
    emails.updateOptional(empty, Optional::of);
    assertEquals(1, queue.tasks.size());
    queue.drain();
    assertTrue(emails.toList(null).isEmpty());
    assertTrue(queue.tasks.isEmpty());
  }

  @Test
  void nullFocusFiltersCompositionAndEarlierSubmissionsSurviveFailure() {
    final var queue = new Queued();
    final var seen = new ArrayList<String>();
    final var tail = Telescope.of(User.class)
      .field(User::email)
      .observeAsync(seen::add, queue, TelescopeObserveAsyncTest::unexpected);
    final var path = Telescope.of(Team.class).each(Team::users).then(tail);
    path.toList(new Team(List.of(new User(null))));
    queue.drain();
    assertEquals(Arrays.asList((String) null), seen);
    seen.clear();
    path.filter("A"::equals).updateOptional(TEAM, value -> Optional.of(value.toLowerCase()));
    queue.drain();
    assertEquals(List.of("a", "B"), seen);
    seen.clear();
    assertThrows(IllegalStateException.class, () ->
      path.update(TEAM, value -> {
        if (value.equals("B")) throw new IllegalStateException();
        return value.toLowerCase();
      })
    );
    queue.drain();
    assertEquals(List.of("a"), seen);
  }

  @Test
  void saturationShutdownAndArgumentValidation() throws Exception {
    final var release = new CountDownLatch(1);
    final var entered = new CountDownLatch(1);
    final var errors = new ArrayList<Throwable>();
    final var worker = new ThreadPoolExecutor(
      1,
      1,
      0,
      TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(1),
      new ThreadPoolExecutor.AbortPolicy()
    );
    try {
      worker.execute(() -> {
        entered.countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      final var path = Telescope.of(Team.class)
        .each(Team::users)
        .observeAsync(user -> {}, worker, (value, failure) -> errors.add(failure));
      assertEquals(2, path.count(TEAM));
      assertEquals(1, errors.size());
      worker.shutdown();
      path.count(TEAM);
      assertEquals(3, errors.size());
    } finally {
      release.countDown();
      worker.close();
    }
    assertThrows(NullPointerException.class, () ->
      Telescope.of(User.class).observeAsync(null, Runnable::run, TelescopeObserveAsyncTest::unexpected)
    );
    assertThrows(NullPointerException.class, () ->
      Telescope.of(User.class).observeAsync(user -> {}, null, TelescopeObserveAsyncTest::unexpected)
    );
    assertThrows(NullPointerException.class, () ->
      Telescope.of(User.class).observeAsync(user -> {}, Runnable::run, null)
    );
  }
}
