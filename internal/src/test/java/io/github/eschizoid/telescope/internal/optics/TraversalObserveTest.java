package io.github.eschizoid.telescope.internal.optics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.internal.optics.collections.Traversals;
import io.github.eschizoid.telescope.runtime.instances.OptionalK;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TraversalObserveTest {

  @Test
  void readsObserveOnlyReachedValuesAndWritesObserveRebuiltValues() {
    final var seen = new ArrayList<Integer>();
    final Traversal<List<Integer>, Integer> observed = Traversals.<Integer>eachList().observe(seen::add);
    final var input = List.of(1, 2, 3);

    assertEquals(input, observed.getAll(input).toList());
    assertEquals(input, seen);
    seen.clear();

    assertEquals(input, observed.getAllForUpdate(input).toList());
    assertEquals(List.of(), seen);
    assertFalse(observed.visitWhile(input, value -> value < 2));
    assertEquals(List.of(1, 2), seen);
    seen.clear();

    assertEquals(List.of(10, 20, 30), observed.modify(input, value -> value * 10));
    assertEquals(List.of(10, 20, 30), seen);
    assertThrows(NullPointerException.class, () -> Traversals.<Integer>eachList().observe(null));
  }

  @Test
  void composedEffectfulUpdateReportsRebuiltParentsIncludingEmptyChildren() {
    final var parents = new ArrayList<List<Integer>>();
    final var children = new ArrayList<Integer>();
    final Traversal<List<List<Integer>>, Integer> observed = Traversals.<List<Integer>>eachList()
      .observe(parents::add)
      .then(
        Traversals.<Integer>eachList()
          .filter(value -> value > 1)
          .observe(children::add)
      );
    final var input = List.of(List.of(1, 2), List.<Integer>of());

    final var updated = OptionalK.unbox(
      observed.modifyF(OptionalK.applicative(), input, value -> OptionalK.box(Optional.of(value * 10)))
    );
    assertEquals(Optional.of(List.of(List.of(1, 20), List.of())), updated);
    assertEquals(List.of(20), children);
    assertEquals(List.of(List.of(1, 20), List.of()), parents);

    parents.clear();
    children.clear();
    final var failed = OptionalK.unbox(
      observed.modifyF(OptionalK.applicative(), input, value -> OptionalK.box(Optional.<Integer>empty()))
    );
    assertEquals(Optional.empty(), failed);
    assertEquals(List.of(), parents);
    assertEquals(List.of(), children);
  }

  @Test
  void filteringAfterObservationStillReportsUnchangedValues() {
    final var seen = new ArrayList<Integer>();
    final var filtered = Traversals.<Integer>eachList()
      .observe(seen::add)
      .filter(value -> value > 1);

    assertEquals(List.of(2), filtered.getAll(List.of(1, 2)).toList());
    assertEquals(List.of(1, 2), seen);
    seen.clear();

    final var updated = OptionalK.unbox(
      filtered.modifyF(OptionalK.applicative(), List.of(1, 2), value -> OptionalK.box(Optional.of(value * 10)))
    );
    assertEquals(Optional.of(List.of(1, 20)), updated);
    assertEquals(List.of(1, 20), seen);

    seen.clear();
    final var noMatch = OptionalK.unbox(
      filtered.modifyF(OptionalK.applicative(), List.of(1), value -> {
        throw new AssertionError("No value passes the filter");
      })
    );
    assertEquals(Optional.of(List.of(1)), noMatch);
    assertEquals(List.of(1), seen);
  }

  @Test
  void innerObserverSurvivesCompositionWithoutOuterObserver() {
    final var seen = new ArrayList<Integer>();
    final var nested = Traversals.<List<Integer>>eachList().then(Traversals.<Integer>eachList().observe(seen::add));

    final var updated = OptionalK.unbox(
      nested.modifyF(OptionalK.applicative(), List.of(List.of(1, 2)), value -> OptionalK.box(Optional.of(value + 1)))
    );
    assertEquals(Optional.of(List.of(List.of(2, 3))), updated);
    assertEquals(List.of(2, 3), seen);
  }
}
