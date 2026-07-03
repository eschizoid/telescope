package io.github.eschizoid.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.PropertySystem.Allocability;
import io.github.eschizoid.telescope.internal.pairing.PropertySystem.WellKnown;
import java.io.Serial;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Direct contract tests for {@link ReflectionProps} — the reflection-world {@link PropertySystem}
 * adapter the runtime mapper construction supplies to {@link PairingRules}. Pins the boxing table,
 * the class-handle discrimination, the parameterized-type decomposition, and the allocability
 * probe's tri-state answers.
 */
class ReflectionPropsTest {

  public static class Urls extends ArrayList<String> {

    @Serial
    private static final long serialVersionUID = 1L;
  }

  public static class UrlsDto extends ArrayList<String> {

    @Serial
    private static final long serialVersionUID = 1L;
  }

  /** Package-private implicit constructor — the allocator probe requires a public one. */
  static class NoPublicCtorUrls extends ArrayList<String> {

    @Serial
    private static final long serialVersionUID = 1L;
  }

  @SuppressWarnings("unused")
  static final class TypeHolder {

    List<String> listOfString;
  }

  private static Type listOfString() {
    try {
      return TypeHolder.class.getDeclaredField("listOfString").getGenericType();
    } catch (final NoSuchFieldException e) {
      throw new IllegalStateException(e);
    }
  }

  private final ReflectionProps props = new ReflectionProps();

  @Nested
  @DisplayName("Boxing table")
  class Boxing {

    @Test
    @DisplayName("every primitive boxes to its wrapper")
    void primitivesBoxToWrappers() {
      assertEquals(Integer.class, props.boxed(int.class));
      assertEquals(Long.class, props.boxed(long.class));
      assertEquals(Double.class, props.boxed(double.class));
      assertEquals(Float.class, props.boxed(float.class));
      assertEquals(Boolean.class, props.boxed(boolean.class));
      assertEquals(Short.class, props.boxed(short.class));
      assertEquals(Byte.class, props.boxed(byte.class));
      assertEquals(Character.class, props.boxed(char.class));
    }

    @Test
    @DisplayName("non-primitives pass through boxed unchanged")
    void nonPrimitivesPassThrough() {
      assertSame(String.class, props.boxed(String.class));
      final var parameterized = listOfString();
      assertSame(parameterized, props.boxed(parameterized));
    }
  }

  @Nested
  @DisplayName("Type-handle discrimination")
  class Discrimination {

    @Test
    @DisplayName("raw Class handles — including primitives and arrays — are class types; parameterized types are not")
    void classTypeBoundary() {
      assertTrue(props.isClassType(String.class));
      assertTrue(props.isClassType(int.class));
      assertTrue(props.isClassType(String[].class));
      assertFalse(props.isClassType(listOfString()));
    }

    @Test
    @DisplayName("isSubtypeOf answers assignability against the well-known handle, false for non-class types")
    void wellKnownSubtyping() {
      assertTrue(props.isSubtypeOf(ArrayList.class, WellKnown.LIST));
      assertTrue(props.isSubtypeOf(ArrayList.class, WellKnown.COLLECTION));
      assertFalse(props.isSubtypeOf(ArrayList.class, WellKnown.SET));
      assertFalse(props.isSubtypeOf(listOfString(), WellKnown.LIST));
    }
  }

  @Nested
  @DisplayName("Parameterized-type decomposition")
  class Decomposition {

    @Test
    @DisplayName("typeArguments returns the actual arguments for parameterized types and nothing for raw handles")
    void typeArgumentsBoundary() {
      assertEquals(List.of((Type) String.class), props.typeArguments(listOfString()));
      assertEquals(List.of(), props.typeArguments(ArrayList.class));
    }

    @Test
    @DisplayName("rawType erases a parameterized type to its raw class and passes raw handles through")
    void rawTypeErasure() {
      assertEquals(List.class, props.rawType(listOfString()));
      assertSame(String.class, props.rawType(String.class));
    }
  }

  @Nested
  @DisplayName("Allocability probe")
  class AllocabilityProbe {

    @Test
    @DisplayName("two user container subclasses with public no-arg constructors are ALLOCABLE")
    void userSubclassesAreAllocable() {
      assertEquals(Allocability.ALLOCABLE, props.copyAllocability(Urls.class, UrlsDto.class));
    }

    @Test
    @DisplayName("a non-class handle on either side is NOT_ALLOCABLE — the reflection world never answers UNKNOWN")
    void nonClassHandleIsNotAllocable() {
      assertEquals(Allocability.NOT_ALLOCABLE, props.copyAllocability(listOfString(), UrlsDto.class));
      assertEquals(Allocability.NOT_ALLOCABLE, props.copyAllocability(Urls.class, listOfString()));
    }

    @Test
    @DisplayName("a side without a public no-arg constructor (or builder) is NOT_ALLOCABLE")
    void missingPublicNoArgCtorIsNotAllocable() {
      assertEquals(Allocability.NOT_ALLOCABLE, props.copyAllocability(Urls.class, NoPublicCtorUrls.class));
      assertEquals(Allocability.NOT_ALLOCABLE, props.copyAllocability(NoPublicCtorUrls.class, Urls.class));
    }
  }
}
