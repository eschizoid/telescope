package io.github.eschizoid.telescope.internal.optics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One write through a composed optic reads each level once. The composition table says a chain of
 * lenses is a lens, which implies a write costs what the depth costs — but a composed {@code set}
 * has to read the outer focus to rebuild it, so a {@code modify} that reaches its focus by reading
 * and then writes by reading again pays for the depth twice over, quadratically as the chain grows.
 *
 * <p>Counting the reads is the only way to see it: the values are identical either way.
 */
class CompositionCostTest {

  /** A level whose read is observable, so the cost of reaching it can be counted. */
  private record Level(Level inner, int leaf) {}

  private static Lens<Level, Level> innerLens(final AtomicInteger reads) {
    return Lens.of(
      source -> {
        reads.incrementAndGet();
        return source.inner();
      },
      (source, value) -> new Level(value, source.leaf())
    );
  }

  private static Lens<Level, Integer> leafLens(final AtomicInteger reads) {
    return Lens.of(
      source -> {
        reads.incrementAndGet();
        return source.leaf();
      },
      (source, value) -> new Level(source.inner(), value)
    );
  }

  private static Level nest(final int depth) {
    var level = new Level(null, 0);
    for (var i = 0; i < depth; i++) level = new Level(level, 0);
    return level;
  }

  private static Lens<Level, Integer> chain(final int depth, final AtomicInteger reads) {
    Lens<Level, Level> path = Lens.of(source -> source, (source, value) -> value);
    for (var i = 0; i < depth; i++) path = path.then(innerLens(reads));
    return path.then(leafLens(reads));
  }

  @Test
  @DisplayName("a composed modify reads each level once, at every depth")
  void modifyReadsAreLinearInDepth() {
    for (final var depth : new int[] { 1, 2, 3, 4, 5, 8 }) {
      final var reads = new AtomicInteger();
      final var path = chain(depth, reads);

      path.modify(nest(depth), leaf -> leaf + 1);

      // depth inner hops plus the leaf: one read each. The quadratic shape would give
      // (depth + 1)(depth + 2) / 2 — 45 at depth 8 rather than 9.
      assertEquals(depth + 1, reads.get(), "modify at depth " + depth + " must read each level once");
    }
  }

  @Test
  @DisplayName("composing through an Iso does not add a read")
  void isoHopIsFree() {
    final var reads = new AtomicInteger();
    final Iso<Integer, Integer> boxed = Iso.of(leaf -> leaf, leaf -> leaf);
    final var path = chain(3, reads).then(boxed);

    path.modify(nest(3), leaf -> leaf + 1);

    assertEquals(4, reads.get(), "an Iso converts rather than reading");
  }

  @Test
  @DisplayName("a chain rooted at an Iso reads each level once too")
  void isoRootedChainIsLinear() {
    // Iso.then(Lens) produces a Lens as well and carries the same hazard — but only when the lens
    // it wraps is itself composed. A plain lens's set performs no read, so an Iso over one costs
    // the same either way; the second read appears once the inner set has to rebuild through a
    // level of its own. Compose the inner chain first, then root it at the Iso.
    final var reads = new AtomicInteger();
    final Iso<Level, Level> identity = Iso.of(level -> level, level -> level);
    final var path = identity.then(innerLens(reads).then(leafLens(reads)));

    path.modify(nest(1), leaf -> leaf + 1);

    assertEquals(2, reads.get(), "the inner hop and the leaf, once each");
  }

  @Test
  @DisplayName("the composed write still produces the right value")
  void valueIsUnchangedByTheCostFix() {
    final var reads = new AtomicInteger();
    final var updated = chain(3, reads).modify(nest(3), leaf -> leaf + 7);

    var level = updated;
    for (var i = 0; i < 3; i++) level = level.inner();
    assertEquals(7, level.leaf(), "the innermost leaf carries the applied function");
  }
}
