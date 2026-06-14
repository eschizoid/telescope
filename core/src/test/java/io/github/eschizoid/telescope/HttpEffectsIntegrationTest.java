package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.effects.Either;
import io.github.eschizoid.telescope.effects.Validated;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end probe of the four effectful-update methods against a real HTTP server. Disabled
 * automatically when Docker isn't available, so this won't block CI environments without it.
 *
 * <p>Uses {@code kennethreitz/httpbin} for predictable endpoints (`/uuid`, `/status/{code}`).
 * Validates that the in-process applicative semantics still hold once real network latency,
 * thread-pool dispatch, and OS sockets are in the loop — not just synthetic {@code
 * CompletableFuture.completedFuture(...)} values.
 *
 * <p><b>Requires a reachable Docker daemon.</b> Linux and macOS Docker Desktop both work out of the
 * box; Testcontainers 2.x autodetects the socket. Without a reachable daemon the test is silently
 * skipped.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Integration: effectful update against a real HTTP server")
class HttpEffectsIntegrationTest {

  @Container
  static final GenericContainer<?> HTTP = new GenericContainer<>(DockerImageName.parse("kennethreitz/httpbin"))
    .withExposedPorts(80)
    .waitingFor(Wait.forHttp("/get").forPort(80));

  static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  record Item(String id, String code) {}

  record Batch(List<Item> items) {}

  static final Telescope<Batch, String> CODES = Telescope.of(Batch.class).each(Batch::items).field(Item::code);

  static URI url(final String path) {
    return URI.create("http://" + HTTP.getHost() + ":" + HTTP.getMappedPort(80) + path);
  }

  static CompletableFuture<HttpResponse<String>> getAsync() {
    return CLIENT.sendAsync(HttpRequest.newBuilder(url("/uuid")).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  static int statusOf(final String path) {
    try {
      return CLIENT.send(
        HttpRequest.newBuilder(url(path)).GET().build(),
        HttpResponse.BodyHandlers.discarding()
      ).statusCode();
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Nested
  @DisplayName("updateAsync — real parallel HTTP calls")
  class AsyncEffect {

    @Test
    @DisplayName("3 parallel /uuid calls produce 3 distinct UUIDs in the rebuilt batch")
    void parallelUuid() throws Exception {
      final var input = new Batch(List.of(new Item("a", "x"), new Item("b", "x"), new Item("c", "x")));

      final CompletableFuture<Batch> done = CODES.updateAsync(input, __ -> getAsync().thenApply(HttpResponse::body));

      final var out = done.get(15, TimeUnit.SECONDS);
      final var codes = out.items().stream().map(Item::code).toList();
      assertEquals(3, codes.size());
      assertEquals(3, codes.stream().distinct().count(), "each /uuid call should return a unique UUID");
    }

    @Test
    @DisplayName("a per-element HTTP failure propagates through the result future")
    void failurePropagates() {
      final var input = new Batch(List.of(new Item("a", "200"), new Item("b", "500"), new Item("c", "200")));

      final CompletableFuture<Batch> done = CODES.updateAsync(input, code ->
        CLIENT.sendAsync(
          HttpRequest.newBuilder(url("/status/" + code)).GET().build(),
          HttpResponse.BodyHandlers.ofString()
        ).thenApply(resp -> {
          if (resp.statusCode() != 200) throw new RuntimeException("server returned " + resp.statusCode());
          return code;
        })
      );

      final var ex = assertThrows(ExecutionException.class, () -> done.get(15, TimeUnit.SECONDS));
      assertTrue(ex.getCause().getMessage().contains("500"), "underlying cause should mention HTTP 500");
    }
  }

  @Nested
  @DisplayName("updateEither — short-circuit on first server failure")
  class EitherEffect {

    @Test
    @DisplayName("first non-2xx wins; an HTTP-call counter proves subsequent items are never called")
    void shortCircuitOn500() {
      final var input = new Batch(List.of(new Item("a", "200"), new Item("b", "500"), new Item("c", "200")));
      final var calls = new AtomicInteger();

      final Either<Integer, Batch> result = CODES.updateEither(input, code -> {
        calls.incrementAndGet();
        final int status = statusOf("/status/" + code);
        return status == 200 ? Either.right(code) : Either.left(status);
      });

      assertInstanceOf(Either.Left.class, result);
      assertEquals(500, ((Either.Left<Integer, Batch>) result).value());
      assertEquals(2, calls.get(), "fn should be invoked for 'a' (Right) and 'b' (Left), then stop — not for 'c'");
    }
  }

  @Nested
  @DisplayName("updateValidated — accumulate every error across the batch")
  class ValidatedEffect {

    @Test
    @DisplayName("every non-200 is collected, in encounter order, and fn IS called on every element")
    void accumulatesEveryError() {
      final var input = new Batch(
        List.of(new Item("a", "200"), new Item("b", "404"), new Item("c", "200"), new Item("d", "503"))
      );
      final var calls = new AtomicInteger();

      final Validated<Integer, Batch> result = CODES.updateValidated(input, code -> {
        calls.incrementAndGet();
        final int status = statusOf("/status/" + code);
        return status == 200 ? Validated.valid(code) : Validated.invalid(status);
      });

      assertInstanceOf(Validated.Invalid.class, result);
      assertEquals(List.of(404, 503), ((Validated.Invalid<Integer, Batch>) result).errors());
      assertEquals(4, calls.get(), "Validated accumulates, so every element must be processed");
    }
  }

  @Nested
  @DisplayName("updateOptional — any empty propagates")
  class OptionalEffect {

    @Test
    @DisplayName("any 204 (No Content) → the whole batch becomes empty")
    void emptyPropagates() {
      final var input = new Batch(List.of(new Item("a", "200"), new Item("b", "204"), new Item("c", "200")));

      final Optional<Batch> result = CODES.updateOptional(input, code -> {
        final int status = statusOf("/status/" + code);
        return status == 200 ? Optional.of(code) : Optional.empty();
      });

      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("all 200s → all items mapped through, batch is present")
    void allPresent() {
      final var input = new Batch(List.of(new Item("a", "200"), new Item("b", "200"), new Item("c", "200")));

      final Optional<Batch> result = CODES.updateOptional(input, code -> {
        final int status = statusOf("/status/" + code);
        return status == 200 ? Optional.of(code) : Optional.empty();
      });

      assertTrue(result.isPresent());
      assertEquals(3, result.get().items().size());
    }
  }
}
