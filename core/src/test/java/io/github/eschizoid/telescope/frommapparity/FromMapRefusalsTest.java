package io.github.eschizoid.telescope.frommapparity;

import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.mapping.MapExtractStep;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Which declared types {@code Telescope.fromMap} fills by itself when no row names the component,
 * and how it refuses the rest. The set is the one the {@code @FromMap} processor serves; a type
 * outside it has no value a map that never carried it could leave behind, so the mapper is refused
 * while it is built rather than handing back a {@code null} nothing explains.
 */
class FromMapRefusalsTest {

  private static final String ROW = "name it with an extract(key, accessor, converter) row";

  enum Tone {
    LOW,
    HIGH,
  }

  /** A record nested in another type, which the processor never generates a binder for. */
  record NestedCity(String city) {}

  record Scalars(
    int i,
    long l,
    double d,
    float f,
    short s,
    byte b,
    boolean z,
    char c,
    Integer boxedInt,
    Long boxedLong,
    Double boxedDouble,
    Float boxedFloat,
    Short boxedShort,
    Byte boxedByte,
    Boolean boxedBool,
    Character boxedChar,
    String text,
    Object any,
    CharSequence chars,
    Tone tone,
    DayOfWeek day,
    DefaultsRow bound
  ) {}

  record StringBuilt(
    Instant instant,
    LocalDate date,
    LocalDateTime dateTime,
    LocalTime time,
    OffsetDateTime offset,
    ZonedDateTime zoned,
    Duration duration,
    Period period,
    UUID uuid,
    BigDecimal decimal,
    BigInteger integer,
    URI uri,
    Currency currency,
    Locale locale,
    Pattern pattern
  ) {}

  record Containers(
    List<String> list,
    Set<Integer> set,
    Optional<String> maybe,
    Map<String, Integer> map,
    Map<DayOfWeek, List<Optional<DefaultsRow>>> nested
  ) {}

  @Nested
  @DisplayName("accepted without a row")
  class Accepted {

    @Test
    @DisplayName("primitives take their zero, references null, including enums and a type with a generated binder")
    void scalarsTakeTheirTypeDefault() {
      final var filled = Telescope.fromMap(Scalars.class).forward(Map.of());

      assertEquals(
        new Scalars(
          0,
          0L,
          0d,
          0f,
          (short) 0,
          (byte) 0,
          false,
          '\0',
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null
        ),
        filled
      );
    }

    @Test
    @DisplayName("every JDK type the generated binder rebuilds from its String form is left null")
    void stringBuiltTypesAreLeftNull() {
      final var filled = Telescope.fromMap(StringBuilt.class).forward(Map.of());

      assertEquals(
        new StringBuilt(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null),
        filled
      );
    }

    @Test
    @DisplayName("List, Set, Optional and Map come back empty, with every type argument checked, the Map key included")
    void containersComeBackEmpty() {
      final var filled = Telescope.fromMap(Containers.class).forward(Map.of());

      assertEquals(new Containers(List.of(), Set.of(), Optional.empty(), Map.of(), Map.of()), filled);
    }

    @Test
    @DisplayName("a bean property of an accepted type is filled the same way a record component is")
    void beanPropertiesAreAccepted() {
      final var filled = Telescope.fromMap(AcceptedBean.class).forward(Map.of());

      assertEquals(0, filled.getCount());
      assertNull(filled.getName());
      assertEquals(List.of(), filled.getTags());
    }
  }

  public static class AcceptedBean {

    private int count;
    private String name;
    private List<String> tags;

    public int getCount() {
      return count;
    }

    public void setCount(final int count) {
      this.count = count;
    }

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public List<String> getTags() {
      return tags;
    }

    public void setTags(final List<String> tags) {
      this.tags = tags;
    }
  }

  record ArrayRec(String[] value) {}

  record GenericArrayRec<T>(T[] value) {}

  @SuppressWarnings("rawtypes")
  record RawListRec(List value) {}

  @SuppressWarnings("rawtypes")
  record RawOptionalRec(Optional value) {}

  record WildcardRec(List<?> value) {}

  record TypeVariableRec<T>(T value) {}

  record ArrayListRec(ArrayList<String> value) {}

  record CollectionRec(Collection<String> value) {}

  record IterableRec(Iterable<String> value) {}

  record NumberRec(Number value) {}

  record JavaxRec(Cipher value) {}

  record UnboundRec(Unbound value) {}

  record NestedClassRec(NestedCity value) {}

  record RefusedElementRec(List<Set<Number>> value) {}

  record RefusedKeyRec(Map<Unbound, String> value) {}

  record RefusedValueRec(Map<String, ArrayList<String>> value) {}

  @SuppressWarnings("rawtypes")
  record RawMapRec(Map value) {}

  record HashMapRec(HashMap<String, String> value) {}

  @Nested
  @DisplayName("refused without a row")
  class Refused {

    private static String refusal(final Class<?> target) {
      return assertThrows(IllegalArgumentException.class, () -> Telescope.fromMap(target)).getMessage();
    }

    private static String prefix(final Class<?> target, final String declared) {
      return (
        "Telescope.fromMap: component 'value' of " +
        target.getSimpleName() +
        " is declared " +
        declared +
        " and no row names it: "
      );
    }

    @Test
    @DisplayName("an array, a type variable and a wildcard argument are refused as kinds no map value coerces into")
    void unsupportedKinds() {
      assertEquals(
        prefix(ArrayRec.class, "String[]") +
          "java.lang.String[] can't be coerced from a map value (type variable / array / unsupported kind); " +
          ROW,
        refusal(ArrayRec.class)
      );
      assertEquals(
        prefix(TypeVariableRec.class, "T") +
          "T can't be coerced from a map value (type variable / array / unsupported kind); " +
          ROW,
        refusal(TypeVariableRec.class)
      );
      assertEquals(
        prefix(GenericArrayRec.class, "T[]") +
          "T[] can't be coerced from a map value (type variable / array / unsupported kind); " +
          ROW,
        refusal(GenericArrayRec.class)
      );
      assertTrue(
        refusal(WildcardRec.class).endsWith(
          ": ? can't be coerced from a map value (type variable / array / unsupported kind); " + ROW
        )
      );
    }

    @Test
    @DisplayName("a raw List, a concrete container and a Collection are refused with the declare-the-interface remedy")
    void collectionSubtypes() {
      assertEquals(
        prefix(ArrayListRec.class, "java.util.ArrayList<java.lang.String>") +
          "java.util.ArrayList is a collection subtype; declare it as List/Set/Map/Optional, or " +
          ROW,
        refusal(ArrayListRec.class)
      );
      assertTrue(
        refusal(RawListRec.class).endsWith(
          ": java.util.List is a collection subtype; declare it as List/Set/Map/Optional, or " + ROW
        )
      );
      assertTrue(
        refusal(CollectionRec.class).endsWith(
          ": java.util.Collection is a collection subtype; declare it as List/Set/Map/Optional, or " + ROW
        )
      );
      assertTrue(
        refusal(RawMapRec.class).endsWith(
          ": java.util.Map is a collection subtype; declare it as List/Set/Map/Optional, or " + ROW
        )
      );
      assertTrue(
        refusal(HashMapRec.class).endsWith(
          ": java.util.HashMap is a collection subtype; declare it as List/Set/Map/Optional, or " + ROW
        )
      );
    }

    @Test
    @DisplayName("a JDK type with no String form the binder rebuilds is refused, under java and javax alike")
    void unbuildableJdkTypes() {
      assertEquals(
        prefix(NumberRec.class, "Number") + "java.lang.Number can't be built from a map value; " + ROW,
        refusal(NumberRec.class)
      );
      assertTrue(refusal(IterableRec.class).endsWith(": java.lang.Iterable can't be built from a map value; " + ROW));
      assertTrue(
        refusal(RawOptionalRec.class).endsWith(": java.util.Optional can't be built from a map value; " + ROW)
      );
      assertTrue(refusal(JavaxRec.class).endsWith(": javax.crypto.Cipher can't be built from a map value; " + ROW));
    }

    @Test
    @DisplayName(
      "a class with no registered binder is refused, top-level or nested, with the annotate and provides remedy"
    )
    void classesWithoutABinder() {
      assertEquals(
        prefix(UnboundRec.class, "Unbound") +
          Unbound.class.getName() +
          " has no registered @FromMap binder; annotate it with @FromMap and recompile (on the module path its" +
          " module-info must also declare \"provides io.github.eschizoid.telescope.conversion.FromMapProvider with " +
          Unbound.class.getName() +
          "FromMap.Provider;\"), or " +
          ROW,
        refusal(UnboundRec.class)
      );
      assertTrue(
        refusal(NestedClassRec.class).contains(NestedCity.class.getName() + " has no registered @FromMap binder")
      );
    }

    @Test
    @DisplayName("a container is refused for a refused element, Map key or Map value, naming the innermost type")
    void refusedTypeArguments() {
      assertTrue(
        refusal(RefusedElementRec.class).endsWith(": java.lang.Number can't be built from a map value; " + ROW)
      );
      assertTrue(
        refusal(RefusedKeyRec.class).contains(": " + Unbound.class.getName() + " has no registered @FromMap binder")
      );
      assertTrue(refusal(RefusedValueRec.class).contains(": java.util.ArrayList is a collection subtype"));
    }

    @Test
    @DisplayName("a bean property of a refused type is refused and named as a property")
    void beanPropertiesAreRefused() {
      assertEquals(
        "Telescope.fromMap: property 'value' of ArrayBean is declared String[] and no row names it: " +
          "java.lang.String[] can't be coerced from a map value (type variable / array / unsupported kind); " +
          ROW,
        assertThrows(IllegalArgumentException.class, () -> Telescope.fromMap(ArrayBean.class)).getMessage()
      );
    }
  }

  public static class ArrayBean {

    private String[] value;

    public String[] getValue() {
      return value;
    }

    public void setValue(final String[] value) {
      this.value = value;
    }
  }

  @Test
  @DisplayName("a refused type a row names is accepted, converted where the key carries a value and null where not")
  void aRowLiftsTheRefusal() {
    final var mapper = Telescope.fromMap(
      ArrayRec.class,
      extract("value", ArrayRec::value, v -> v.toString().split(","))
    );

    assertArrayEquals(new String[] { "a", "b" }, mapper.forward(Map.of("value", "a,b")).value());
    assertNull(mapper.forward(Map.of()).value());

    final var bean = Telescope.fromMap(
      ArrayBean.class,
      extract("value", ArrayBean::getValue, v -> v.toString().split(","))
    );
    assertArrayEquals(new String[] { "x" }, bean.forward(Map.of("value", "x")).getValue());
  }

  public static class GenericBean<T> {

    private T value;

    public T getValue() {
      return value;
    }

    public void setValue(final T value) {
      this.value = value;
    }
  }

  @Test
  @DisplayName("a type-variable bean property a row names is converted when present and null when absent")
  void aRowNamedTypeVariablePropertyDefaultsToNull() {
    // A type variable has no class behind it, so there is no container family to fill it from and
    // no primitive default to fall back on: an absent key leaves the reference null.
    @SuppressWarnings("unchecked")
    final Class<GenericBean<String>> target = (Class<GenericBean<String>>) (Class<?>) GenericBean.class;
    final var mapper = Telescope.fromMap(target, extract("value", GenericBean<String>::getValue, Object::toString));

    assertEquals("x", mapper.forward(Map.of("value", "x")).getValue());
    assertNull(mapper.forward(Map.of()).getValue());
  }

  @Test
  @DisplayName("a null source map gives a null target on the record and the bean path")
  void aNullMapGivesANullTarget() {
    assertNull(Telescope.fromMap(Scalars.class).forward(null));
    assertNull(Telescope.fromMap(AcceptedBean.class).forward(null));
  }

  @Test
  @DisplayName("a null row is refused with the factory to build one from")
  void aNullRowIsRefused() {
    final var refusal = assertThrows(IllegalArgumentException.class, () ->
      Telescope.fromMap(Scalars.class, (MapExtractStep) null)
    );
    assertEquals(
      "Telescope.fromMap rows must be built via MapExtractStep.extract(...) or MapExtractStep.required(...)",
      refusal.getMessage()
    );
  }
}
