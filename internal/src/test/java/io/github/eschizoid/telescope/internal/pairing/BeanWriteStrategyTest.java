package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.BeanWriteStrategy.Member;
import io.github.eschizoid.telescope.internal.pairing.BeanWriteStrategy.Shape;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shared decision on its own, over hand-written shapes, so each branch of the order and of the
 * builder's skip rule is pinned without a bean behind it.
 */
class BeanWriteStrategyTest {

  /** A bean with two properties, {@code a} and {@code b}, described fact by fact. */
  private record Facts(
    boolean hasBuilder,
    Map<String, Member> members,
    boolean hasConstructor,
    boolean hasSetters,
    Set<String> setterWrites,
    Set<String> stored
  ) implements Shape {
    @Override
    public List<String> properties() {
      return List.of("a", "b");
    }

    @Override
    public Member builderMember(final String property) {
      return members.getOrDefault(property, Member.ABSENT);
    }

    @Override
    public boolean setterWrites(final String property) {
      return setterWrites.contains(property);
    }

    @Override
    public boolean stored(final String property) {
      return stored.contains(property);
    }
  }

  private static final Map<String, Member> BOTH = Map.of("a", Member.ACCEPTS, "b", Member.ACCEPTS);
  private static final Map<String, Member> ONLY_A = Map.of("a", Member.ACCEPTS);

  private static Optional<BeanWriteStrategy> auto(final Facts facts) {
    return BeanWriteStrategy.auto(facts);
  }

  @Test
  @DisplayName("the order is builder, constructor, setters")
  void order() {
    assertEquals(
      List.of(BeanWriteStrategy.BUILDER, BeanWriteStrategy.CONSTRUCTOR, BeanWriteStrategy.SETTERS),
      BeanWriteStrategy.AUTO_ORDER
    );
    assertEquals(
      Optional.of(BeanWriteStrategy.BUILDER),
      auto(new Facts(true, BOTH, true, true, Set.of("a", "b"), Set.of()))
    );
    assertEquals(
      Optional.of(BeanWriteStrategy.CONSTRUCTOR),
      auto(new Facts(false, Map.of(), true, true, Set.of(), Set.of()))
    );
    assertEquals(
      Optional.of(BeanWriteStrategy.SETTERS),
      auto(new Facts(false, Map.of(), false, true, Set.of(), Set.of()))
    );
    assertTrue(auto(new Facts(false, Map.of(), false, false, Set.of(), Set.of())).isEmpty());
  }

  @Test
  @DisplayName("a rejecting member passes the builder over whatever the next strategy writes")
  void rejectingMember() {
    final var rejects = Map.of("a", Member.ACCEPTS, "b", Member.REJECTS);
    assertEquals(
      Optional.of(BeanWriteStrategy.SETTERS),
      auto(new Facts(true, rejects, false, true, Set.of(), Set.of()))
    );
  }

  @Test
  @DisplayName("an absent member passes the builder over when a constructor would write the property")
  void absentAgainstAConstructor() {
    assertEquals(
      Optional.of(BeanWriteStrategy.CONSTRUCTOR),
      auto(new Facts(true, ONLY_A, true, false, Set.of(), Set.of()))
    );
  }

  @Test
  @DisplayName("against setters, an absent member counts only for a property a setter writes")
  void absentAgainstSetters() {
    assertEquals(
      Optional.of(BeanWriteStrategy.SETTERS),
      auto(new Facts(true, ONLY_A, false, true, Set.of("b"), Set.of()))
    );
    assertEquals(
      Optional.of(BeanWriteStrategy.BUILDER),
      auto(new Facts(true, ONLY_A, false, true, Set.of("a"), Set.of("b")))
    );
  }

  @Test
  @DisplayName("with nothing after it, an absent member counts only for a stored property")
  void absentWithNothingAfter() {
    assertTrue(auto(new Facts(true, ONLY_A, false, false, Set.of(), Set.of("b"))).isEmpty());
    assertEquals(
      Optional.of(BeanWriteStrategy.BUILDER),
      auto(new Facts(true, ONLY_A, false, false, Set.of(), Set.of()))
    );
  }
}
