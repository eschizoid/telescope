package io.github.eschizoid.telescope.internal.optics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

/**
 * The executable form of {@link Fold}'s documented contract: "both primitives enumerate the same
 * focuses in the same order." Apply it to every optic — a future override that lets {@code
 * visitWhile} drift from {@code getAll} fails here instead of surfacing as a silent read-terminal
 * divergence in the DSL.
 */
final class FoldLaws {

  private FoldLaws() {}

  /**
   * Assert the fold laws for {@code fold} against one source: (1) {@code visitWhile} visits exactly
   * the focuses {@code getAll} streams, in the same order; (2) a full visit reports completion; (3)
   * a first-focus short-circuit stops after exactly one focus and reports the stop — or reports
   * completion when there are no focuses at all.
   */
  static <S, A> void assertFoldLaws(final Fold<S, A> fold, final S source) {
    final var streamed = new ArrayList<A>();
    fold.getAll(source).forEach(streamed::add);

    final var visited = new ArrayList<A>();
    final var completed = fold.visitWhile(source, a -> {
      visited.add(a);
      return true;
    });

    assertEquals(streamed, visited, "visitWhile must enumerate exactly what getAll streams, in order");
    assertTrue(completed, "an all-true visit must report completion");

    final var seen = new ArrayList<A>();
    final var fullyVisited = fold.visitWhile(source, a -> {
      seen.add(a);
      return false; // stop at the first focus
    });
    if (streamed.isEmpty()) {
      assertTrue(fullyVisited, "no focuses: nothing to stop at, the visit completes");
      assertTrue(seen.isEmpty());
    } else {
      assertEquals(1, seen.size(), "a false-returning visitor must stop after the first focus");
      assertEquals(streamed.get(0), seen.get(0), "the stopped-at focus is getAll's head");
      assertFalse(fullyVisited, "a short-circuited visit must report the stop");
    }
  }
}
