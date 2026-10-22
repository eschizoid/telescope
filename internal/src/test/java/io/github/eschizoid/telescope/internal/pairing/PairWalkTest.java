package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.PairWalk.KeyedRow;
import io.github.eschizoid.telescope.internal.pairing.PairWalk.Pair;
import io.github.eschizoid.telescope.internal.pairing.PairWalk.RowKind;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The pairs the shared walk visits, in the reflection world. */
class PairWalkTest {

  private static final PairWalk<Type> WALK = new PairWalk<>(new ReflectionProps());

  record Leaf(String name, String label) {}

  record LeafDto(String name) {}

  record Other(String name) {}

  record OtherDto(String name) {}

  record Outer(Leaf leaf, String note) {}

  record OuterDto(LeafDto leaf, String note) {}

  record Renamed(LeafDto renamed, String note) {}

  record Containers(
    List<Leaf> items,
    Optional<Leaf> maybe,
    Map<String, Leaf> byKey,
    Optional<Leaf> present,
    Leaf plain
  ) {}

  record ContainersDto(
    List<LeafDto> items,
    LeafDto maybe,
    Map<String, LeafDto> byKey,
    Optional<LeafDto> present,
    Optional<LeafDto> plain
  ) {}

  record Node(String n, Node next) {}

  record NodeDto(String n, NodeDto next) {}

  record Mismatch(Leaf leaf, Integer count) {}

  record MismatchDto(LeafDto leaf, List<String> count) {}

  /** A bean source whose getters name its properties. */
  public static final class LeafBean {

    public String getName() {
      return null;
    }

    public boolean isActive() {
      return false;
    }
  }

  /** A bean target. */
  public static final class LeafBeanDto {

    public String getTitle() {
      return null;
    }

    public void setTitle(final String title) {}
  }

  record BeanOuter(LeafBean leaf) {}

  record BeanOuterDto(LeafBeanDto leaf) {}

  /** A generic bean whose property is typed by its variable. */
  public static class Box<T> {

    public T getInner() {
      return null;
    }
  }

  /** Fixes the variable to {@link Leaf}. */
  public static final class LeafBox extends Box<Leaf> {}

  record BoxedDto(LeafDto inner) {}

  record Held<T>(T v, Leaf leaf) {}

  record HeldDto<T>(T v, LeafDto leaf) {}

  record HoldsHeld(Held<String> held) {}

  record HoldsHeldDto(HeldDto<String> held) {}

  sealed interface Shape permits Circle, Square {}

  record Circle(Leaf leaf) implements Shape {}

  record Square(Other other) implements Shape {}

  sealed interface ShapeDto permits CircleDto, SquareDto {}

  record CircleDto(LeafDto leaf) implements ShapeDto {}

  record SquareDto(OtherDto other) implements ShapeDto {}

  record Drawing(Shape shape) {}

  record DrawingDto(ShapeDto shape) {}

  /** Answers as a world with no bridges, pairing each case of a source by the map it is given. */
  private static class Answers implements PairWalk.Decisions<Type> {

    final Set<Class<?>> bridged;
    final Map<Class<?>, Class<?>> cases;
    final List<List<Pair<Type>>> asked = new ArrayList<>();
    final List<String> filled = new ArrayList<>();

    Answers(final Set<Class<?>> bridged, final Map<Class<?>, Class<?>> cases) {
      this.bridged = bridged;
      this.cases = cases;
    }

    @Override
    public boolean ownBridge(final Type source, final Type target, final List<Pair<Type>> around) {
      asked.add(List.copyOf(around));
      return bridged.contains((Class<?>) source);
    }

    @Override
    public List<Pair<Type>> casePairs(final Type sourceRoot, final Type targetRoot) {
      if (cases == null) return null;
      final var pairs = new ArrayList<Pair<Type>>();
      for (final var sourceCase : ((Class<?>) sourceRoot).getPermittedSubclasses()) {
        pairs.add(new Pair<>(sourceCase, cases.get(sourceCase)));
      }
      return pairs;
    }

    @Override
    public void fills(final Type source, final Type target, final String sourceProperty, final String targetProperty) {
      filled.add(
        ((Class<?>) source).getSimpleName() +
          "." +
          sourceProperty +
          " -> " +
          ((Class<?>) target).getSimpleName() +
          "." +
          targetProperty
      );
    }
  }

  private static Answers plain() {
    return new Answers(Set.of(), Map.of());
  }

  private static KeyedRow<Type> row(
    final Class<?> source,
    final Class<?> target,
    final RowKind kind,
    final String sourceAccessor,
    final String targetAccessor
  ) {
    return new KeyedRow<>(source, target, kind, sourceAccessor, targetAccessor);
  }

  private static List<Pair<Type>> pairs(final Class<?>... classes) {
    final var pairs = new ArrayList<Pair<Type>>();
    for (var i = 0; i < classes.length; i += 2) pairs.add(new Pair<>(classes[i], classes[i + 1]));
    return pairs;
  }

  @Nested
  @DisplayName("The walk visits")
  class Visits {

    @Test
    @DisplayName("the mapper's own pair and the pair a same-named property leads to")
    void nestedPair() {
      final var walk = WALK.walk(Outer.class, OuterDto.class, List.of(), false, plain());
      assertEquals(pairs(Outer.class, OuterDto.class, Leaf.class, LeafDto.class), walk.visited());
      assertFalse(walk.open());
      assertNull(walk.refusal());
    }

    @Test
    @DisplayName("each element pair a list, an Optional, a map's values and an Optional bridge lead to, once")
    void containerElements() {
      final var walk = WALK.walk(Containers.class, ContainersDto.class, List.of(), false, plain());
      assertEquals(pairs(Containers.class, ContainersDto.class, Leaf.class, LeafDto.class), walk.visited());
    }

    @Test
    @DisplayName("a pair a pass-through row renames its way to")
    void passThroughRow() {
      final var rows = List.of(
        row(Outer.class, Renamed.class, RowKind.PASS_THROUGH, "leaf", "renamed"),
        row(Leaf.class, LeafDto.class, RowKind.PASS_THROUGH, "label", "name")
      );
      final var walk = WALK.walk(Outer.class, Renamed.class, rows, false, plain());
      assertEquals(pairs(Outer.class, Renamed.class, Leaf.class, LeafDto.class), walk.visited());
      assertNull(walk.refusal());
    }

    @Test
    @DisplayName("each pair of a cycle once")
    void cycle() {
      final var walk = WALK.walk(Node.class, NodeDto.class, List.of(), false, plain());
      assertEquals(pairs(Node.class, NodeDto.class), walk.visited());
    }

    @Test
    @DisplayName("the pair a generic superclass's property leads to, typed as the subclass fixes it")
    void inheritedVariable() {
      final var walk = WALK.walk(LeafBox.class, BoxedDto.class, List.of(), true, plain());
      assertEquals(pairs(LeafBox.class, BoxedDto.class, Leaf.class, LeafDto.class), walk.visited());
    }

    @Test
    @DisplayName("a pair given type arguments as its classes")
    void genericPair() {
      final var walk = WALK.walk(HoldsHeld.class, HoldsHeldDto.class, List.of(), false, plain());
      assertEquals(
        pairs(HoldsHeld.class, HoldsHeldDto.class, Held.class, HeldDto.class, Leaf.class, LeafDto.class),
        walk.visited()
      );
    }

    @Test
    @DisplayName("past a property the shared decision refuses, which leaves the walk open and refuses no row")
    void pastAnIncompatibleProperty() {
      final var rows = List.of(row(Other.class, OtherDto.class, RowKind.PASS_THROUGH, "name", "name"));
      final var walk = WALK.walk(Mismatch.class, MismatchDto.class, rows, false, plain());
      assertEquals(pairs(Mismatch.class, MismatchDto.class, Leaf.class, LeafDto.class), walk.visited());
      assertTrue(walk.open());
      assertEquals(List.of(), walk.neverVisited());
      assertNull(walk.refusal());
    }

    @Test
    @DisplayName("a pair of sealed interfaces case by case, as the world pairs the cases")
    void sealedCases() {
      final var answers = new Answers(Set.of(), Map.of(Circle.class, CircleDto.class, Square.class, SquareDto.class));
      final var walk = WALK.walk(Drawing.class, DrawingDto.class, List.of(), false, answers);
      assertEquals(
        pairs(
          Drawing.class,
          DrawingDto.class,
          Shape.class,
          ShapeDto.class,
          Circle.class,
          CircleDto.class,
          Leaf.class,
          LeafDto.class,
          Square.class,
          SquareDto.class,
          Other.class,
          OtherDto.class
        ),
        walk.visited()
      );
      assertEquals(pairs(Shape.class, ShapeDto.class), walk.dispatched());
    }

    @Test
    @DisplayName("a mapper's own pair of sealed interfaces case by case")
    void sealedRoot() {
      final var answers = new Answers(Set.of(), Map.of(Circle.class, CircleDto.class, Square.class, SquareDto.class));
      final var walk = WALK.walk(Shape.class, ShapeDto.class, List.of(), false, answers);
      assertEquals(pairs(Shape.class, ShapeDto.class), walk.dispatched());
      assertEquals(
        pairs(
          Shape.class,
          ShapeDto.class,
          Circle.class,
          CircleDto.class,
          Leaf.class,
          LeafDto.class,
          Square.class,
          SquareDto.class,
          Other.class,
          OtherDto.class
        ),
        walk.visited()
      );
    }
  }

  @Nested
  @DisplayName("The walk does not visit")
  class DoesNotVisit {

    @Test
    @DisplayName("below a property a row with a conversion of its own claims")
    void belowAConvertingRow() {
      final var rows = List.of(row(Outer.class, OuterDto.class, RowKind.CONVERTING, "leaf", "leaf"));
      final var walk = WALK.walk(Outer.class, OuterDto.class, rows, false, plain());
      assertEquals(pairs(Outer.class, OuterDto.class), walk.visited());
    }

    @Test
    @DisplayName("below a pair whose own bridge stands in for the walk")
    void belowABridge() {
      final var answers = new Answers(Set.of(Leaf.class), Map.of());
      final var walk = WALK.walk(Outer.class, OuterDto.class, List.of(), false, answers);
      assertEquals(pairs(Outer.class, OuterDto.class), walk.visited());
      assertEquals(List.of(pairs(Outer.class, OuterDto.class)), answers.asked);
    }

    @Test
    @DisplayName("below a pair of sealed interfaces whose cases the world cannot pair, and refuses nothing")
    void belowUnpairedCases() {
      final var rows = List.of(row(Other.class, LeafDto.class, RowKind.PASS_THROUGH, "name", "name"));
      final var walk = WALK.walk(Drawing.class, DrawingDto.class, rows, false, new Answers(Set.of(), null));
      assertTrue(walk.open());
      assertEquals(pairs(Drawing.class, DrawingDto.class, Shape.class, ShapeDto.class), walk.visited());
      assertEquals(List.of(), walk.neverVisited());
      assertNull(walk.refusal());
    }
  }

  @Test
  @DisplayName("a bridge is asked about with the pairs being walked around it, innermost first")
  void bridgeAskedWithAncestors() {
    final var answers = plain();
    WALK.walk(HoldsHeld.class, HoldsHeldDto.class, List.of(), false, answers);
    assertEquals(
      List.of(
        pairs(HoldsHeld.class, HoldsHeldDto.class),
        pairs(Held.class, HeldDto.class, HoldsHeld.class, HoldsHeldDto.class)
      ),
      answers.asked
    );
  }

  @Test
  @DisplayName("a row through a path claims nothing, so the same-named property is still followed")
  void pathRowClaimsNothing() {
    final var rows = List.of(
      row(Outer.class, OuterDto.class, RowKind.PATH, null, null),
      row(Leaf.class, LeafDto.class, RowKind.PASS_THROUGH, "label", "name")
    );
    final var walk = WALK.walk(Outer.class, OuterDto.class, rows, false, plain());
    assertNull(walk.refusal());
  }

  @Test
  @DisplayName("each property filled is told, a bean's by the property its accessor names")
  void fills() {
    final var answers = plain();
    final var rows = List.of(
      row(LeafBean.class, LeafBeanDto.class, RowKind.CONVERTING, "getName", "getTitle"),
      row(BeanOuter.class, BeanOuterDto.class, RowKind.DROP, "leaf", null)
    );
    WALK.walk(BeanOuter.class, BeanOuterDto.class, rows, false, answers);
    assertEquals(List.of("BeanOuter.leaf -> BeanOuterDto.leaf", "LeafBean.name -> LeafBeanDto.title"), answers.filled);
  }

  @Test
  @DisplayName("a row naming a property neither class has leads nowhere")
  void unknownProperty() {
    final var rows = List.of(row(Outer.class, OuterDto.class, RowKind.PASS_THROUGH, "missing", "leaf"));
    final var walk = WALK.walk(Outer.class, OuterDto.class, rows, false, plain());
    assertEquals(pairs(Outer.class, OuterDto.class), walk.visited());
  }

  @Nested
  @DisplayName("Rows keyed to a pair the walk never visits")
  class NeverVisited {

    @Test
    @DisplayName("are refused by binary name, each pair in the order its first row was given, drop rows aside")
    void refusal() {
      final var rows = List.of(
        row(Other.class, OtherDto.class, RowKind.DROP, "name", null),
        row(Outer.class, OuterDto.class, RowKind.CONVERTING, "leaf", "leaf"),
        row(Leaf.class, LeafDto.class, RowKind.PASS_THROUGH, "label", "name"),
        row(Other.class, OtherDto.class, RowKind.CONVERTING, "name", "name"),
        row(Leaf.class, LeafDto.class, RowKind.PATH, null, "name")
      );
      final var walk = WALK.walk(Outer.class, OuterDto.class, rows, false, plain());
      assertEquals(List.of(rows.get(2), rows.get(3), rows.get(4)), walk.neverVisited());
      assertEquals(
        PairingMessages.rowsNeverVisited(
          Outer.class.getName(),
          OuterDto.class.getName(),
          List.of(
            PairingMessages.unvisitedPair(Other.class.getName(), OtherDto.class.getName(), List.of("name -> name")),
            PairingMessages.unvisitedPair(
              Leaf.class.getName(),
              LeafDto.class.getName(),
              List.of("label -> name", "<path> -> name")
            )
          )
        ),
        walk.refusal()
      );
    }

    @Test
    @DisplayName("are not refused when every one is a drop row")
    void onlyDrops() {
      final var rows = List.of(row(Other.class, OtherDto.class, RowKind.DROP, "name", null));
      assertNull(WALK.walk(Outer.class, OuterDto.class, rows, false, plain()).refusal());
    }
  }

  @Nested
  @DisplayName("The reflection world lists a class's properties")
  class Properties {

    private final ReflectionProps props = new ReflectionProps();

    @Test
    @DisplayName("a record's components, typed the same way twice")
    void recordComponents() {
      assertEquals(
        List.of(
          new Property<Type>("name", String.class, String.class),
          new Property<Type>("label", String.class, String.class)
        ),
        props.properties(Leaf.class)
      );
    }

    @Test
    @DisplayName("a bean's getters, typed as members of the class and as declared")
    void beanGetters() {
      final var properties = props.properties(LeafBox.class);
      assertEquals(1, properties.size());
      assertEquals("inner", properties.getFirst().name());
      assertEquals(Leaf.class, properties.getFirst().member());
      assertEquals("T", properties.getFirst().declared().getTypeName());
    }

    @Test
    @DisplayName("nothing for a type that names no record or bean")
    void nothingElse() {
      assertEquals(List.of(), props.properties(Shape.class));
      assertEquals(List.of(), props.properties(int.class));
      assertEquals(List.of(), props.properties(Leaf[].class));
    }
  }
}
