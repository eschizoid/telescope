package io.github.eschizoid.telescope.internal.optics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.eschizoid.telescope.effects.Validated;
import io.github.eschizoid.telescope.internal.optics.collections.Traversals;
import io.github.eschizoid.telescope.runtime.instances.CompletableFutureK;
import io.github.eschizoid.telescope.runtime.instances.OptionalK;
import io.github.eschizoid.telescope.runtime.instances.ValidatedK;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Direct coverage of the many-focus {@link Traversal#modifyF} default — the positional-array
 * accumulator that lifts a traversal over an effect. The public {@code Telescope} effect terminals
 * exercise this end-to-end too, but those tests live in another module; pinning it here at the
 * substrate level proves the order-preservation invariant and the per-effect semantics (empty
 * early-return, short-circuit break, error accumulation, sequential rebuild) where the code lives.
 */
class TraversalModifyFTest {

  private static final Traversal<List<Integer>, Integer> EACH = Traversals.eachList();

  @Test
  @DisplayName("empty focus returns pure(source) without invoking fn")
  void emptyFocusReturnsPureWithoutInvokingFn() {
    final var calls = new int[] { 0 };
    final var result = OptionalK.unbox(
      EACH.modifyF(OptionalK.applicative(), List.of(), a -> {
        calls[0]++;
        return OptionalK.box(Optional.of(a));
      })
    );
    assertEquals(Optional.of(List.of()), result);
    assertEquals(0, calls[0], "fn must not be called when there are no foci");
  }

  @Test
  @DisplayName("all-present Optional rebuilds every slot in getAll order")
  void allPresentRebuildsEverySlotInOrder() {
    final var result = OptionalK.unbox(
      EACH.modifyF(OptionalK.applicative(), List.of(1, 2, 3, 4, 5), a -> OptionalK.box(Optional.of(a * 10)))
    );
    assertEquals(Optional.of(List.of(10, 20, 30, 40, 50)), result);
  }

  @Test
  @DisplayName("Optional.empty mid-traversal short-circuits the remaining foci")
  void optionalEmptyMidTraversalShortCircuits() {
    final var calls = new int[] { 0 };
    final var result = OptionalK.unbox(
      EACH.modifyF(OptionalK.applicative(), List.of(1, 2, 3), a -> {
        calls[0]++;
        final Optional<Integer> next = a == 2 ? Optional.empty() : Optional.of(a);
        return OptionalK.box(next);
      })
    );
    assertEquals(Optional.empty(), result);
    assertEquals(2, calls[0], "fn must stop being called once the accumulator is empty");
  }

  @Test
  @DisplayName("Validated accumulates every focus error (no short-circuit)")
  void validatedAccumulatesEveryFocusError() {
    final var result = ValidatedK.unbox(
      EACH.modifyF(ValidatedK.forError(), List.of(1, 2, 3, 4), a -> {
        final Validated<String, Integer> v = a % 2 == 0 ? Validated.valid(a) : Validated.invalid("odd: " + a);
        return ValidatedK.box(v);
      })
    );
    assertInstanceOf(Validated.Invalid.class, result);
    assertEquals(List.of("odd: 1", "odd: 3"), ((Validated.Invalid<String, ?>) result).errors());
  }

  @Test
  @DisplayName("CompletableFuture rebuilds every slot, order preserved")
  void completableFutureRebuildsEverySlotInOrder() throws Exception {
    final var result = CompletableFutureK.unbox(
      EACH.modifyF(CompletableFutureK.applicative(), List.of(1, 2, 3), a ->
        CompletableFutureK.box(CompletableFuture.completedFuture(a + 100))
      )
    ).get();
    assertEquals(List.of(101, 102, 103), result);
  }
}
