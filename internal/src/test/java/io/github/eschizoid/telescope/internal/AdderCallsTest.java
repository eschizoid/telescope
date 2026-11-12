package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The adder calls the adders strategy makes, and the deferral {@code into} builds its value under:
 * no adder of the deferred class runs on the value built, its elements come back instead, every
 * other instance of that class built on the way has its adders called once the build ends, and the
 * thread is left as it was found, whether the build returns or throws.
 */
class AdderCallsTest {

  /** A bean that records each element its adder is handed. */
  private static class Owner {

    final List<Object> added = new ArrayList<>();
  }

  private static final class Child extends Owner {}

  private static final class Other {

    final List<Object> added = new ArrayList<>();
  }

  private static final BiConsumer<Object, Object> ADD = (owner, element) -> ((Owner) owner).added.add(element);

  private static final BiConsumer<Object, Object> ADD_OTHER = (owner, element) -> ((Other) owner).added.add(element);

  @Test
  @DisplayName("with no deferral armed, each element goes to the adder at once, and null adds nothing")
  void withNoDeferralTheAdderRunsAtOnce() {
    final var owner = new Owner();
    AdderCalls.add(owner, "lines", List.of("a", "b"), ADD);
    AdderCalls.add(owner, "lines", null, ADD);
    assertEquals(List.of("a", "b"), owner.added);
  }

  @Test
  @DisplayName("the value built runs none of its adders, and comes back with what they were to be handed")
  void theValueBuiltRunsNoAdder() {
    final var lines = List.of("a", "b");
    final var deferred = AdderCalls.deferring(Owner.class, () -> {
      final var built = new Owner();
      AdderCalls.add(built, "lines", lines, ADD);
      return built;
    });
    assertEquals(List.of(), deferred.value().added);
    assertSame(lines, deferred.elements().get("lines"));
  }

  @Test
  @DisplayName("another instance of the class built on the way has its adders called, in order, after the build")
  void otherInstancesAreReplayedInOrder() {
    final var first = new Owner();
    final var second = new Child();
    final var deferred = AdderCalls.deferring(Owner.class, () -> {
      AdderCalls.add(first, "children", List.of("x"), ADD);
      AdderCalls.add(second, "children", List.of("y", "z"), ADD);
      assertEquals(List.of(), first.added, "nothing runs while the build is under way");
      final var root = new Owner();
      AdderCalls.add(root, "children", List.of(first, second), ADD);
      return root;
    });
    assertEquals(List.of("x"), first.added);
    assertEquals(List.of("y", "z"), second.added);
    assertEquals(List.of(), deferred.value().added);
    assertEquals(List.of(first, second), deferred.elements().get("children"));
  }

  @Test
  @DisplayName("an instance of a class the deferral does not name has its adder called at once")
  void anotherClassIsNotDeferred() {
    final var other = new Other();
    AdderCalls.deferring(Owner.class, () -> {
      AdderCalls.add(other, "lines", List.of("a"), ADD_OTHER);
      assertEquals(List.of("a"), other.added);
      return new Owner();
    });
  }

  @Test
  @DisplayName("an adder of another class runs with no deferral armed, so a value it builds is filled at once")
  void anAdderOfAnotherClassRunsUndeferred() {
    final var builtInside = new ArrayList<Owner>();
    final BiConsumer<Object, Object> building = (other, element) -> {
      final var inner = new Owner();
      AdderCalls.add(inner, "lines", List.of(element), ADD);
      builtInside.add(inner);
    };
    final var deferred = AdderCalls.deferring(Owner.class, () -> {
      AdderCalls.add(new Other(), "lines", List.of("i"), building);
      assertEquals(List.of("i"), builtInside.getFirst().added, "filled as it was built, not once the build ends");
      final var root = new Owner();
      AdderCalls.add(root, "lines", List.of("r"), ADD);
      return root;
    });
    assertEquals(List.of(), deferred.value().added, "the deferral is armed again after the other adder");
    assertEquals(List.of("r"), deferred.elements().get("lines"));
  }

  @Test
  @DisplayName("a value whose adders were never called comes back with no elements")
  void aValueWithNoAdderCallsHasNoElements() {
    assertEquals(Map.of(), AdderCalls.deferring(Owner.class, Owner::new).elements());
  }

  @Test
  @DisplayName("a build that throws leaves the thread with no deferral armed")
  void aThrowingBuildDisarms() {
    final var failure = new IllegalStateException("build failed");
    assertSame(
      failure,
      assertThrows(IllegalStateException.class, () ->
        AdderCalls.deferring(Owner.class, () -> {
          throw failure;
        })
      )
    );
    final var after = new Owner();
    AdderCalls.add(after, "lines", List.of("a"), ADD);
    assertEquals(List.of("a"), after.added);
  }

  @Test
  @DisplayName("a deferral inside another restores the outer one when it ends")
  void anInnerDeferralRestoresTheOuter() {
    final var outerChild = new Owner();
    final var deferred = AdderCalls.deferring(Owner.class, () -> {
      final var inner = AdderCalls.deferring(Other.class, () -> {
        final var built = new Other();
        AdderCalls.add(built, "lines", List.of("i"), ADD_OTHER);
        return built;
      });
      assertEquals(List.of(), inner.value().added);
      // The outer deferral is armed again: this owner's adder waits for the outer build to
      // end.
      AdderCalls.add(outerChild, "lines", List.of("o"), ADD);
      assertTrue(outerChild.added.isEmpty());
      return new Owner();
    });
    assertEquals(List.of("o"), outerChild.added);
    assertEquals(Map.of(), deferred.elements());
  }
}
