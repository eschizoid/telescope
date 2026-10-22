package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.PairWalk.Pair;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Whether a nested pair converts through its own bridge, in the reflection world. */
class OwnBridgeRuleTest {

  private static final ReflectionProps PROPS = new ReflectionProps();
  private static final PairingRules<Type> RULES = new PairingRules<>(PROPS);
  private static final PairWalk<Type> WALK = new PairWalk<>(PROPS);

  /** A bridge that walks every property and does not take the active path. */
  private static final OwnBridgeRule.Bridge WALKS_ALL = new OwnBridgeRule.Bridge(true, Set.of(), Set.of(), false);

  record Part(String v) {}

  record PartDto(String v) {}

  record Inner(String name, Part part) {}

  record InnerDto(String name, PartDto part) {}

  record Outer(Inner in) {}

  record OuterDto(InnerDto in) {}

  sealed interface Shape permits Circle {}

  record Circle(Part centre) implements Shape {}

  sealed interface ShapeDto permits CircleDto {}

  record CircleDto(PartDto centre) implements ShapeDto {}

  record Drawing(Shape shape) {}

  record DrawingDto(ShapeDto shape) {}

  record Upper(List<? extends Part> parts) {}

  record Lower(List<? super Part> parts) {}

  record Unbounded(List<?> parts) {}

  record Bounded<P extends Part>(P part) {}

  record Arrays(Part[] parts, List<Part>[] lists) {}

  static class Parts extends ArrayList<Part> {

    private static final long serialVersionUID = 1L;
  }

  record Holder(Parts parts) {}

  private static OwnBridgeRule<Type> rule(
    final String everyPair,
    final List<Pair<Type>> keyed,
    final List<Pair<Type>> caseRows,
    final List<Type> hinted
  ) {
    return new OwnBridgeRule<>(PROPS, RULES, WALK, everyPair, keyed, caseRows, hinted);
  }

  private static OwnBridgeRule<Type> plain() {
    return rule(null, List.of(), List.of(), List.of());
  }

  private static Pair<Type> pair(final Class<?> source, final Class<?> target) {
    return new Pair<>(source, target);
  }

  @Nested
  @DisplayName("What the mapper says of the pair itself")
  class Configured {

    @Test
    @DisplayName("a setting reaching every pair is the reason, whatever else holds")
    void everyPair() {
      assertEquals(
        "every pair",
        rule("every pair", List.of(pair(Inner.class, InnerDto.class)), List.of(), List.of()).configuredReason(
          Inner.class,
          InnerDto.class
        )
      );
    }

    @Test
    @DisplayName("a row keyed to the pair")
    void keyed() {
      assertEquals(
        "a row is keyed to the pair",
        rule(null, List.of(pair(Inner.class, InnerDto.class)), List.of(), List.of()).configuredReason(
          Inner.class,
          InnerDto.class
        )
      );
    }

    @Test
    @DisplayName("a row keyed to two sealed interfaces the pair's classes implement")
    void inherited() {
      assertEquals(
        "a row keyed to two sealed interfaces it implements applies to the pair",
        rule(null, List.of(pair(Shape.class, ShapeDto.class)), List.of(), List.of()).configuredReason(
          Circle.class,
          CircleDto.class
        )
      );
    }

    @Test
    @DisplayName("a write hint naming the source, then one naming the target")
    void hinted() {
      assertEquals(
        "a writeBean hint names Inner",
        rule(null, List.of(), List.of(), List.of(Inner.class)).configuredReason(Inner.class, InnerDto.class)
      );
      assertEquals(
        "a writeBean hint names InnerDto",
        rule(null, List.of(), List.of(), List.of(InnerDto.class)).configuredReason(Inner.class, InnerDto.class)
      );
    }

    @Test
    @DisplayName("nothing said of the pair, and a row keyed to another pair, give no reason")
    void none() {
      assertNull(
        rule(null, List.of(pair(Part.class, PartDto.class)), List.of(), List.of()).configuredReason(
          Inner.class,
          InnerDto.class
        )
      );
    }

    @Test
    @DisplayName("the pair met again inside itself is a reason the pair alone does not give")
    void metAgain() {
      final var rule = plain();
      assertNull(rule.settingReason(Inner.class, InnerDto.class, List.of(pair(Outer.class, OuterDto.class))));
      assertEquals(
        "the pair is met again inside itself",
        rule.settingReason(Inner.class, InnerDto.class, List.of(pair(Inner.class, InnerDto.class)))
      );
      assertEquals(
        "a row is keyed to the pair",
        rule(null, List.of(pair(Inner.class, InnerDto.class)), List.of(), List.of()).settingReason(
          Inner.class,
          InnerDto.class,
          List.of()
        )
      );
    }
  }

  @Nested
  @DisplayName("What the pair's classes reach through the properties its bridge walks")
  class Reach {

    @Test
    @DisplayName("a row keyed to a pair both sides reach, unless the bridge does not walk the property")
    void keyedReached() {
      final var rule = rule(null, List.of(pair(Part.class, PartDto.class)), List.of(), List.of());
      assertEquals(
        "a row is keyed to (Part, PartDto), a pair it reaches",
        rule.reachReason(Inner.class, InnerDto.class, List.of(), WALKS_ALL)
      );
      assertNull(
        rule.reachReason(
          Inner.class,
          InnerDto.class,
          List.of(),
          new OwnBridgeRule.Bridge(true, Set.of("part"), Set.of(), false)
        )
      );
      assertNull(
        rule.reachReason(
          Inner.class,
          InnerDto.class,
          List.of(),
          new OwnBridgeRule.Bridge(true, Set.of(), Set.of("part"), false)
        )
      );
    }

    @Test
    @DisplayName("a bridge that does not list what it leaves unwalked is read as walking every property")
    void unlisted() {
      assertEquals(
        "a row is keyed to (Part, PartDto), a pair it reaches",
        rule(null, List.of(pair(Part.class, PartDto.class)), List.of(), List.of()).reachReason(
          Inner.class,
          InnerDto.class,
          List.of(),
          new OwnBridgeRule.Bridge(false, Set.of("part"), Set.of("part"), false)
        )
      );
    }

    @Test
    @DisplayName("a cases row pairing two classes both sides reach")
    void casesReached() {
      assertEquals(
        "a cases row pairs Part with PartDto, a pair it reaches",
        rule(null, List.of(), List.of(pair(Part.class, PartDto.class)), List.of()).reachReason(
          Inner.class,
          InnerDto.class,
          List.of(),
          WALKS_ALL
        )
      );
    }

    @Test
    @DisplayName("a write hint naming a class either side reaches")
    void hintReached() {
      assertEquals(
        "a writeBean hint names PartDto, a class it reaches",
        rule(null, List.of(), List.of(), List.of(PartDto.class)).reachReason(
          Inner.class,
          InnerDto.class,
          List.of(),
          WALKS_ALL
        )
      );
    }

    @Test
    @DisplayName("a pair converted around it that it reaches, unless the bridge takes the active path")
    void ancestorReached() {
      final var around = List.of(pair(Inner.class, InnerDto.class), pair(Part.class, PartDto.class));
      assertEquals(
        "it reaches (Part, PartDto), a pair being converted around it",
        plain().reachReason(Inner.class, InnerDto.class, around, WALKS_ALL)
      );
      assertNull(
        plain().reachReason(
          Inner.class,
          InnerDto.class,
          around,
          new OwnBridgeRule.Bridge(true, Set.of(), Set.of(), true)
        )
      );
      assertNull(
        plain().reachReason(Inner.class, InnerDto.class, List.of(pair(Outer.class, OuterDto.class)), WALKS_ALL)
      );
    }
  }

  @Nested
  @DisplayName("The classes a value can hold")
  class ReachedFrom {

    private static String name(final Class<?> cls) {
      return cls.getName();
    }

    @Test
    @DisplayName("records on the way are read, and a skipped property only on the class asked about")
    void skippedOnStartOnly() {
      final var rule = plain();
      assertTrue(rule.reachedFrom(Outer.class, Set.of(), false).contains(name(Part.class)));
      assertFalse(rule.reachedFrom(Inner.class, Set.of("part"), false).contains(name(Part.class)));
      assertTrue(rule.reachedFrom(Outer.class, Set.of("part"), false).contains(name(Part.class)));
    }

    @Test
    @DisplayName("a sealed interface is read through to its cases only when asked, and a case does not hold its own")
    void throughCases() {
      final var rule = plain();
      final var through = rule.reachedFrom(Drawing.class, Set.of(), true);
      assertTrue(through.contains(name(Circle.class)));
      assertTrue(through.contains(name(Part.class)));
      final var not = rule.reachedFrom(Drawing.class, Set.of(), false);
      assertTrue(not.contains(name(Shape.class)));
      assertFalse(not.contains(name(Part.class)));
      assertFalse(rule.reachedFrom(Circle.class, Set.of(), true).contains(name(Shape.class)));
      assertTrue(rule.reachedFrom(Circle.class, Set.of(), false).contains(name(Shape.class)));
    }

    @Test
    @DisplayName("a wildcard's bounds, an unwritten one read as Object, and a type variable's bounds")
    void boundsRead() {
      final var rule = plain();
      assertTrue(rule.reachedFrom(Upper.class, Set.of(), false).contains(name(Part.class)));
      assertTrue(rule.reachedFrom(Lower.class, Set.of(), false).contains(name(Part.class)));
      assertTrue(rule.reachedFrom(Unbounded.class, Set.of(), false).contains(name(Object.class)));
      assertTrue(rule.reachedFrom(Bounded.class, Set.of(), false).contains(name(Part.class)));
    }

    @Test
    @DisplayName("an array's component, a generic array's, and the arguments a class passes to what it extends")
    void componentsAndSupertypes() {
      final var rule = plain();
      final var arrays = rule.reachedFrom(Arrays.class, Set.of(), false);
      assertTrue(arrays.contains(name(Part.class)));
      assertTrue(arrays.contains(name(List.class)));
      assertTrue(rule.reachedFrom(Holder.class, Set.of(), false).contains(name(Part.class)));
    }

    @Test
    @DisplayName("a JDK class is counted but not read")
    void jdkNotRead() {
      final var reached = plain().reachedFrom(Inner.class, Set.of(), false);
      assertTrue(reached.contains(name(String.class)));
      assertFalse(reached.contains(name(byte[].class)));
    }

    @Test
    @DisplayName("a reach once read is kept")
    void kept() {
      final var rule = plain();
      assertSame(rule.reachedFrom(Outer.class, Set.of(), false), rule.reachedFrom(Outer.class, Set.of(), false));
    }
  }
}
