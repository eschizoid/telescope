package io.github.eschizoid.telescope.internal.pairing;

import static io.github.eschizoid.telescope.internal.pairing.CollectionWrites.IntoWrite.REFILL;
import static io.github.eschizoid.telescope.internal.pairing.CollectionWrites.IntoWrite.REFUSE;
import static io.github.eschizoid.telescope.internal.pairing.CollectionWrites.IntoWrite.SET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
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
 * The shared decisions for writing a container into an existing target, over reflection handles:
 * which properties are refilled, which are tied to their owner element by element, and how {@code
 * into} writes one.
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
      for (final var field : List.of("optional", "text")) assertFalse(WRITES.refilledInPlace(declared(field)), field);
    }

    @Test
    @DisplayName("a collection is tied to its owner element by element, never a map, an optional or a value")
    void onlyACollectionIsLinked() throws NoSuchFieldException {
      for (final var field : List.of("strings", "integers", "numbers", "raw")) {
        assertTrue(WRITES.linked(declared(field)), field);
      }
      for (final var field : List.of("map", "sortedMap", "optional", "text")) {
        assertFalse(WRITES.linked(declared(field)), field);
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
  @DisplayName("What into does once a refill has failed")
  class AfterAFailedRefill {

    @Test
    @DisplayName("an adder's rejection is thrown on, setter or not, so nothing writes past the adder")
    void anAdderRejectionIsThrownOn() {
      assertEquals(CollectionWrites.AfterFailedRefill.RETHROW, CollectionWrites.afterFailedRefill(true, true));
      assertEquals(CollectionWrites.AfterFailedRefill.RETHROW, CollectionWrites.afterFailedRefill(true, false));
    }

    @Test
    @DisplayName("a container's own refusal goes to the setter, and is refused by name without one")
    void aContainerRefusalGoesToTheSetterOrIsRefused() {
      assertEquals(CollectionWrites.AfterFailedRefill.SET, CollectionWrites.afterFailedRefill(false, true));
      assertEquals(CollectionWrites.AfterFailedRefill.REFUSE, CollectionWrites.afterFailedRefill(false, false));
    }
  }
}
