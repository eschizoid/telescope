package io.github.eschizoid.telescope.internal.pairing;

import static io.github.eschizoid.telescope.internal.pairing.CollectionWrites.IntoWrite.REFILL;
import static io.github.eschizoid.telescope.internal.pairing.CollectionWrites.IntoWrite.REFUSE;
import static io.github.eschizoid.telescope.internal.pairing.CollectionWrites.IntoWrite.SET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The shared decisions for writing a container into an existing target and for filling a collection
 * through an adder, over reflection handles: which properties are refilled, which an adder fills,
 * how {@code into} writes one, and which method is the adder.
 */
class CollectionWritesTest {

  private static final CollectionWrites<Type> WRITES = new CollectionWrites<>(new ReflectionProps());

  /** Declarations whose generic types the tests read. */
  @SuppressWarnings({ "unused", "rawtypes" })
  private static final class Declared {

    List<String> strings;
    Set<Integer> integers;
    Collection<Number> numbers;
    List<? extends Number> boundedWildcard;
    List<?> anyWildcard;
    List<Object> objects;
    List raw;
    Map<String, Integer> map;
    SortedMap<String, Integer> sortedMap;
    Optional<String> optional;
    String text;
  }

  private static Type declared(final String field) throws NoSuchFieldException {
    return Declared.class.getDeclaredField(field).getGenericType();
  }

  @Nested
  @DisplayName("Which properties into refills and which an adder fills")
  class Kinds {

    @Test
    @DisplayName("a collection or a map is refilled in place, and nothing else is")
    void collectionsAndMapsAreRefilled() throws NoSuchFieldException {
      for (final var field : List.of("strings", "integers", "numbers", "raw", "map", "sortedMap")) {
        assertTrue(WRITES.refilledInPlace(declared(field)), field);
      }
      for (final var field : List.of("optional", "text")) {
        assertFalse(WRITES.refilledInPlace(declared(field)), field);
      }
    }

    @Test
    @DisplayName("an adder fills a collection, never a map, an optional or a value")
    void anAdderFillsCollectionsOnly() throws NoSuchFieldException {
      for (final var field : List.of("strings", "integers", "numbers", "raw")) {
        assertTrue(WRITES.filledByAdder(declared(field)), field);
      }
      for (final var field : List.of("map", "sortedMap", "optional", "text")) {
        assertFalse(WRITES.filledByAdder(declared(field)), field);
      }
    }

    @Test
    @DisplayName("the element an adder takes is the declared element, a wildcard's bound, or none for Object")
    void theElementAnAdderTakes() throws NoSuchFieldException {
      assertEquals(String.class, WRITES.elementOf(declared("strings")));
      assertEquals(Integer.class, WRITES.elementOf(declared("integers")));
      assertEquals(Number.class, WRITES.elementOf(declared("boundedWildcard")));
      assertNull(WRITES.elementOf(declared("anyWildcard")));
      assertNull(WRITES.elementOf(declared("objects")));
      assertNull(WRITES.elementOf(declared("raw")));
    }
  }

  @Nested
  @DisplayName("How into writes a container property")
  class IntoWrites {

    @Test
    @DisplayName("a changeable held container is refilled whenever a value comes, or no setter could write the null")
    void aChangeableHeldContainerIsRefilled() {
      for (final var setter : List.of(true, false)) {
        for (final var adder : List.of(true, false)) {
          assertEquals(REFILL, CollectionWrites.intoWrite(true, true, true, setter, adder));
        }
      }
      assertEquals(REFILL, CollectionWrites.intoWrite(true, true, false, false, false));
      assertEquals(REFILL, CollectionWrites.intoWrite(true, true, false, true, true));
      assertEquals(REFILL, CollectionWrites.intoWrite(true, true, false, false, true));
    }

    @Test
    @DisplayName("a null value with a setter and no adder is written through the setter, as forward gives it")
    void aNullValueGoesThroughTheSetter() {
      assertEquals(SET, CollectionWrites.intoWrite(true, true, false, true, false));
    }

    @Test
    @DisplayName("a target holding no container is filled through its adder where one fills the property")
    void noHeldContainerIsFilledThroughTheAdder() {
      for (final var value : List.of(true, false)) {
        for (final var setter : List.of(true, false)) {
          assertEquals(REFILL, CollectionWrites.intoWrite(false, false, value, setter, true));
        }
      }
    }

    @Test
    @DisplayName("no container, or one that cannot be changed, goes through the setter, and is refused without one")
    void anUnwritableContainerIsSetOrRefused() {
      for (final var value : List.of(true, false)) {
        assertEquals(SET, CollectionWrites.intoWrite(false, false, value, true, false));
        assertEquals(REFUSE, CollectionWrites.intoWrite(false, false, value, false, false));
        for (final var adder : List.of(true, false)) {
          assertEquals(SET, CollectionWrites.intoWrite(true, false, value, true, adder));
          assertEquals(REFUSE, CollectionWrites.intoWrite(true, false, value, false, adder));
        }
      }
    }
  }

  @Nested
  @DisplayName("Which method is the adder")
  class Adders {

    @Test
    @DisplayName("an adder is looked for under the name made singular, then as it is, each name once")
    void adderNamesMakeThePropertySingular() {
      assertEquals(List.of("addLine", "addLin", "addLines"), CollectionWrites.adderNames("lines"));
      assertEquals(
        List.of("addCategory", "addCategorie", "addCategori", "addCategories"),
        CollectionWrites.adderNames("categories")
      );
      assertEquals(List.of("addAddresse", "addAddress", "addAddresses"), CollectionWrites.adderNames("addresses"));
      assertEquals(List.of("addData"), CollectionWrites.adderNames("data"));
      assertEquals(List.of("addS"), CollectionWrites.adderNames("s"));
      assertEquals(List.of("addE", "addEs"), CollectionWrites.adderNames("es"));
    }

    /** Candidate adders of every shape the choice tells apart. */
    @SuppressWarnings("unused")
    private static final class Candidates {

      public void addLine(final String line) {}

      public void addLine(final Object line) {}

      public void addItem(final CharSequence item) {}

      public void addItem(final Comparable<?> item) {}

      public void addCount(final int count) {}

      public void addCounts(final Number count) {}

      public void addWide(final Object wide) {}
    }

    private static Method adderFor(final String property, final Type element) {
      return WRITES.adderFor(
        property,
        element,
        name ->
          Arrays.stream(Candidates.class.getDeclaredMethods())
            .filter(method -> method.getName().equals(name))
            .toList(),
        method -> method.getGenericParameterTypes()[0]
      );
    }

    @Test
    @DisplayName("a method taking exactly the element's class is taken over a wider one of the same name")
    void theExactParameterWins() throws NoSuchMethodException {
      assertEquals(Candidates.class.getMethod("addLine", String.class), adderFor("lines", String.class));
    }

    @Test
    @DisplayName("two methods equally able to take the element, neither exactly, choose none")
    void twoWiderMethodsChooseNone() {
      assertNull(adderFor("items", String.class));
    }

    @Test
    @DisplayName("a primitive parameter is passed over, and the next name is asked")
    void aPrimitiveParameterIsPassedOver() throws NoSuchMethodException {
      assertEquals(Candidates.class.getMethod("addCounts", Number.class), adderFor("counts", Integer.class));
    }

    @Test
    @DisplayName("a method that cannot take the element is no adder")
    void aMethodThatCannotTakeTheElementIsNone() {
      assertNull(adderFor("counts", String.class));
      assertNull(adderFor("missing", String.class));
    }

    @Test
    @DisplayName("an element of no declared class is taken by the one reference parameter of the name")
    void anUnknownElementTakesAnyReferenceParameter() throws NoSuchMethodException {
      assertEquals(Candidates.class.getMethod("addWide", Object.class), adderFor("wides", null));
      assertNull(adderFor("lines", null), "two reference parameters of one name are no single choice");
    }
  }
}
