package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract pins for {@link NullDefaults}: the JLS-default substitution table behind {@code
 * NullHint.NullStrategy#DEFAULT}. Every test below names the real-world regression it prevents —
 * the table is consulted on every null-source / non-null-target pair the deep-mapping engine
 * encounters, so a silent change to any entry below shifts adopter semantics.
 */
class NullDefaultsTest {

  @Nested
  @DisplayName("Primitive wrappers: boxed-zero defaults prevent unboxing NPE during record construction")
  class PrimitiveWrappers {

    @Test
    @DisplayName("Integer + int both default to boxed 0 — null source paired with primitive int target")
    void integerAndIntDefault() {
      // Real scenario: record Order(int qty) mapped from a OrderDto where Integer qty == null.
      // Without this substitution the canonical-ctor call NPEs on the boxed-null unbox. The test
      // pins BOTH wrapper and primitive — a refactor that handled only one shape would silently
      // break the other.
      assertEquals(0, NullDefaults.defaultFor(Integer.class));
      assertEquals(0, NullDefaults.defaultFor(int.class));
    }

    @Test
    @DisplayName(
      "Long / Double / Float / Short / Byte all default to their boxed-zero forms with the right wrapper type"
    )
    void allNumericWrappersHaveBoxedZero() {
      // Wrapper type matters, not just numeric equality — adopters' downstream code casts to
      // specific wrappers (e.g. (Long) val for a DB primary-key column). A refactor that returned
      // Integer.valueOf(0) for every numeric default would compile but ClassCastException at
      // first use on a Long field.
      assertEquals(0L, NullDefaults.defaultFor(Long.class));
      assertEquals(0L, NullDefaults.defaultFor(long.class));
      assertSame(Long.class, NullDefaults.defaultFor(Long.class).getClass());

      assertEquals(0.0d, NullDefaults.defaultFor(Double.class));
      assertEquals(0.0d, NullDefaults.defaultFor(double.class));
      assertSame(Double.class, NullDefaults.defaultFor(Double.class).getClass());

      assertEquals(0.0f, NullDefaults.defaultFor(Float.class));
      assertEquals(0.0f, NullDefaults.defaultFor(float.class));
      assertSame(Float.class, NullDefaults.defaultFor(Float.class).getClass());

      assertEquals((short) 0, NullDefaults.defaultFor(Short.class));
      assertEquals((short) 0, NullDefaults.defaultFor(short.class));
      assertSame(Short.class, NullDefaults.defaultFor(Short.class).getClass());

      assertEquals((byte) 0, NullDefaults.defaultFor(Byte.class));
      assertEquals((byte) 0, NullDefaults.defaultFor(byte.class));
      assertSame(Byte.class, NullDefaults.defaultFor(Byte.class).getClass());
    }

    @Test
    @DisplayName("Boolean + boolean default to false (NOT Boolean.FALSE Object identity — adopters compare by value)")
    void booleanDefault() {
      assertEquals(Boolean.FALSE, NullDefaults.defaultFor(Boolean.class));
      assertEquals(Boolean.FALSE, NullDefaults.defaultFor(boolean.class));
    }

    @Test
    @DisplayName("Character is DELIBERATELY absent from the table — javadoc claim that adopters depend on")
    void characterIsAbsent() {
      // Per the class javadoc: Character is omitted on purpose; null character source values fall
      // through to null, and primitive char targets retain the JLS-default '\0' from the rebuild
      // path. A future PR "adding consistency by filling in Character → '\0'" would silently change
      // adopter semantics on null-character source values. Pinning the deliberate absence guards
      // against that refactor.
      assertNull(NullDefaults.defaultFor(Character.class), "Character must NOT be in the substitution table");
      assertNull(NullDefaults.defaultFor(char.class), "char must NOT be in the substitution table");
    }
  }

  @Nested
  @DisplayName("String + BigDecimal + BigInteger: enterprise-DB-column defaults")
  class EnterpriseColumns {

    @Test
    @DisplayName("String defaults to the empty string literal — matches MapStruct's nullable-VARCHAR convention")
    void stringDefault() {
      final var actual = NullDefaults.defaultFor(String.class);
      assertEquals("", actual);
      // Identity check — the literal "" lives in the constant pool, not a fresh new String(). A
      // future swap to `new String("")` would compile but break adopters who string-intern compare.
      assertSame("", actual);
    }

    @Test
    @DisplayName("BigDecimal / BigInteger return the canonical ZERO singletons (identity, not just .equals)")
    void bigNumericZeroIdentity() {
      // Identity matters: adopters with JPA-attached entities use BigDecimal.ZERO as a sentinel for
      // "this column was nulled". A future "spec-compliant new BigDecimal(0)" rewrite would break
      // the sentinel pattern silently.
      assertSame(BigDecimal.ZERO, NullDefaults.defaultFor(BigDecimal.class));
      assertSame(BigInteger.ZERO, NullDefaults.defaultFor(BigInteger.class));
    }
  }

  @Nested
  @DisplayName("Container defaults: immutable JDK empty singletons across all write paths")
  class ContainerDefaults {

    @Test
    @DisplayName("List defaults to the immutable List.of() singleton — assignable subtypes also route through here")
    void listAndSubtypesDefaultToImmutableEmpty() {
      final var listDefault = NullDefaults.defaultFor(List.class);
      assertEquals(List.of(), listDefault);
      // Stable across write paths: List.of() returns the same singleton each call. If a future
      // refactor used new ArrayList<>(), adopters mutating that result would silently corrupt
      // shared state when the same default fired twice.
      assertSame(List.of(), listDefault);
      // ArrayList is a List subtype — isAssignableFrom branch must route through.
      assertEquals(List.of(), NullDefaults.defaultFor(ArrayList.class));
      // Immutability check — pin that the singleton refuses mutation. A new ArrayList<>() swap
      // would compile but adopter-side .add(...) calls would silently start succeeding.
      @SuppressWarnings("unchecked")
      final var asList = (List<Object>) listDefault;
      assertThrows(UnsupportedOperationException.class, () -> asList.add("x"));
    }

    @Test
    @DisplayName("Set + Map + their subtypes default to their immutable empty singletons")
    void setAndMapDefaults() {
      assertSame(Set.of(), NullDefaults.defaultFor(Set.class));
      assertSame(Set.of(), NullDefaults.defaultFor(HashSet.class));
      assertSame(Map.of(), NullDefaults.defaultFor(Map.class));
      assertSame(Map.of(), NullDefaults.defaultFor(HashMap.class));
    }

    @Test
    @DisplayName("Optional defaults to Optional.empty() — NOT Optional.of(null) or Optional.ofNullable(null)")
    void optionalDefault() {
      assertSame(Optional.empty(), NullDefaults.defaultFor(Optional.class));
    }
  }

  @Nested
  @DisplayName("ParameterizedType unwrap: container fields with generic parameters route via raw type")
  class ParameterizedTypeBranch {

    record Holder(List<String> xs, Map<String, Integer> ys) {}

    @Test
    @DisplayName("RecordComponent.getGenericType() returning a ParameterizedType still resolves via the raw class")
    void parameterizedTypeUnwrapsToRawClass() {
      // Real consumer path: NullDefaults is called with `RecordComponent.getGenericType()` for
      // container fields, which is a ParameterizedType. The unwrap branch (line 65) must route the
      // raw class lookup. A regression that handled only Class arguments would silently return
      // null for every `List<X>` / `Map<K, V>` field, breaking the empty-singleton contract.
      final var listComponent = Holder.class.getRecordComponents()[0];
      final Type genericListType = listComponent.getGenericType();
      assertInstanceOf(ParameterizedType.class, genericListType, "precondition: List<String> is a ParameterizedType");
      assertSame(List.of(), NullDefaults.defaultFor(genericListType));

      final var mapComponent = Holder.class.getRecordComponents()[1];
      final Type genericMapType = mapComponent.getGenericType();
      assertInstanceOf(ParameterizedType.class, genericMapType);
      assertSame(Map.of(), NullDefaults.defaultFor(genericMapType));
    }
  }

  @Nested
  @DisplayName("Catch-all: unknown leaf types must return null (no fabricated defaults)")
  class UnknownLeafTypes {

    enum Status {
      ACTIVE,
      INACTIVE,
    }

    record Inner(String x) {}

    @Test
    @DisplayName(
      "UUID / records / enums / arrays all return null — fabricating a default would corrupt adopter semantics"
    )
    void unknownLeafTypesReturnNull() {
      // Per the class javadoc: substituting a non-null default for these would require fabricating
      // an instance (records/beans) or picking an arbitrary canonical (enums, UUID) that almost
      // never matches the user's intent. The right ergonomic shape is for adopters to declare an
      // explicit `Mapping.toOrElse` for non-trivial defaults.
      assertNull(NullDefaults.defaultFor(UUID.class));
      assertNull(NullDefaults.defaultFor(Inner.class));
      assertNull(NullDefaults.defaultFor(Status.class));
      assertNull(NullDefaults.defaultFor(int[].class));
      assertNull(NullDefaults.defaultFor(Object.class));
    }

    @Test
    @DisplayName("Type sub-interfaces that aren't Class or ParameterizedType also return null — catch-all")
    void unsupportedTypeKindReturnsNull() {
      // TypeVariable / GenericArrayType / WildcardType implementations all reach the catch-all
      // `return null` at line 68. Construct one via reflective probing of a generic field so we
      // hit a real Type instance rather than mocking the interface.
      final var typeVarHolder = TypeVarHolder.class.getTypeParameters()[0];
      assertNull(NullDefaults.defaultFor(typeVarHolder));
    }

    static class TypeVarHolder<T> {}
  }
}
