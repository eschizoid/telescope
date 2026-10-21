package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.ParameterizedType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What {@link Beans} says about the member a property is written through: its class, what it takes
 * in place of null, and the property's type read through a subclass's own variables.
 */
class WrittenMemberTest {

  public record IntRec(int value) {}

  /** Built through its all-args constructor, the only way it can be. */
  public static final class Constructed {

    private final int value;

    public Constructed(final int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  /** Built through a builder whose member for the property takes a primitive. */
  public static final class Built {

    private final int value;

    private Built(final int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private int value;

      public Builder value(final int v) {
        this.value = v;
        return this;
      }

      public Built build() {
        return new Built(value);
      }
    }
  }

  /** Written through a primitive setter, which skips a null rather than taking a default. */
  public static final class Set {

    private int value;

    public Set() {}

    public int getValue() {
      return value;
    }

    public void setValue(final int value) {
      this.value = value;
    }
  }

  /** A builder whose overload for the property's type sorts after one for Object. */
  public static final class StringBuilt {

    private final String value;

    private StringBuilt(final String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private String value;

      public Builder value(final Object v) {
        this.value = "Object:" + v;
        return this;
      }

      public Builder value(final String v) {
        this.value = "String:" + v;
        return this;
      }

      public StringBuilt build() {
        return new StringBuilt(value);
      }
    }
  }

  /** A builder whose overload for the property's type sorts before one for String. */
  public static final class CharSequenceBuilt {

    private final CharSequence value;

    private CharSequenceBuilt(final CharSequence value) {
      this.value = value;
    }

    public CharSequence getValue() {
      return value;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private CharSequence value;

      public Builder value(final String v) {
        this.value = "String:" + v;
        return this;
      }

      public Builder value(final CharSequence v) {
        this.value = "CharSequence:" + v;
        return this;
      }

      public CharSequenceBuilt build() {
        return new CharSequenceBuilt(value);
      }
    }
  }

  /** A property typed Object, which any value fits. */
  public static final class Anything {

    private Object value;

    public Anything() {}

    public Object getValue() {
      return value;
    }

    public void setValue(final Object value) {
      this.value = value;
    }
  }

  /** A property whose only setter takes its value as the one element of an array. */
  public static final class Spread {

    private String value;

    public Spread() {}

    public String getValue() {
      return value;
    }

    public void setValue(final String... values) {
      this.value = values[0];
    }
  }

  /** Two setters that take the property's type equally, so neither is chosen. */
  public static final class EquallySet {

    private String value;

    public EquallySet() {}

    public String getValue() {
      return value;
    }

    public void setValue(final Comparable<?> v) {
      this.value = "Comparable:" + v;
    }

    public void setValue(final CharSequence v) {
      this.value = "CharSequence:" + v;
    }
  }

  /** A builder with two overloads that take the property's type equally. */
  public static final class EquallyBuilt {

    private final String value;

    private EquallyBuilt(final String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private String value;

      public Builder value(final Comparable<?> v) {
        this.value = "Comparable:" + v;
        return this;
      }

      public Builder value(final CharSequence v) {
        this.value = "CharSequence:" + v;
        return this;
      }

      public EquallyBuilt build() {
        return new EquallyBuilt(value);
      }
    }
  }

  public static class Base<V> {

    private List<V> value;

    public List<V> getValue() {
      return value;
    }

    public void setValue(final List<V> value) {
      this.value = value;
    }
  }

  public static class Bounded<T extends Integer> extends Base<T> {}

  @Nested
  @DisplayName("writtenNullValue — what a member takes in place of a null value")
  class NullValue {

    @Test
    @DisplayName("a primitive record component takes its default")
    void aRecordComponentTakesTheDefault() {
      assertEquals(0, Beans.writtenNullValue(Reflective.RECORDS.writerFor(IntRec.class), IntRec.class, "value"));
    }

    @Test
    @DisplayName("a primitive constructor parameter takes its default")
    void aConstructorParameterTakesTheDefault() {
      final var writer = Reflective.BEANS.writerFor(Constructed.class);
      assertInstanceOf(Beans.ConstructorWriter.class, writer);
      assertEquals(0, Beans.writtenNullValue(writer, Constructed.class, "value"));
    }

    @Test
    @DisplayName("a primitive builder member takes its default")
    void aBuilderMemberTakesTheDefault() {
      final var writer = Reflective.BEANS.writerFor(Built.class);
      assertInstanceOf(Beans.BuilderWriter.class, writer);
      assertEquals(0, Beans.writtenNullValue(writer, Built.class, "value"));
    }

    @Test
    @DisplayName("a primitive setter takes nothing, since it skips a null")
    void aSetterTakesNothing() {
      final var writer = Reflective.BEANS.writerFor(Set.class);
      assertInstanceOf(Beans.SettersWriter.class, writer);
      assertNull(Beans.writtenNullValue(writer, Set.class, "value"));
    }

    @Test
    @DisplayName("a reference member takes nothing")
    void aReferenceMemberTakesNothing() {
      final var writer = Reflective.BEANS.writerFor(StringBuilt.class);
      assertNull(Beans.writtenNullValue(writer, StringBuilt.class, "value"));
    }
  }

  @Nested
  @DisplayName("writtenClass — the class the chosen member takes")
  class WrittenClass {

    @Test
    @DisplayName("a primitive member answers its wrapper")
    void aPrimitiveAnswersItsWrapper() {
      assertEquals(
        Integer.class,
        Beans.writtenClass(Reflective.RECORDS.writerFor(IntRec.class), IntRec.class, "value")
      );
      assertEquals(Integer.class, Beans.writtenClass(Reflective.BEANS.writerFor(Set.class), Set.class, "value"));
    }

    @Test
    @DisplayName("a member taking Object fits anything, so nothing is checked against it")
    void anObjectMemberAnswersNothing() {
      assertNull(Beans.writtenClass(Reflective.BEANS.writerFor(Anything.class), Anything.class, "value"));
    }

    @Test
    @DisplayName("setters the property's type takes equally choose none, so nothing is checked against them")
    void equallyTakingSettersChooseNone() {
      assertNull(Beans.writtenClass(Reflective.BEANS.writerFor(EquallySet.class), EquallySet.class, "value"));
    }

    @Test
    @DisplayName("a value spread into a variable-arity setter is checked against the array's component")
    void aSpreadSetterAnswersItsComponent() {
      assertEquals(String.class, Beans.writtenClass(Reflective.BEANS.writerFor(Spread.class), Spread.class, "value"));
    }

    @Test
    @DisplayName("builder overloads the property's type takes equally fall back to the first in a fixed order")
    void equallyTakingBuilderOverloadsFallBackToTheFirst() {
      final var writer = Reflective.BEANS.writerFor(EquallyBuilt.class);
      assertEquals(CharSequence.class, Beans.writtenClass(writer, EquallyBuilt.class, "value"));
    }

    @Test
    @DisplayName("a builder writes through the overload its property's type chooses, where that sorts last")
    void theChosenBuilderOverloadSortingLast() {
      final var writer = Reflective.BEANS.writerFor(StringBuilt.class);
      assertEquals(String.class, Beans.writtenClass(writer, StringBuilt.class, "value"));
    }

    @Test
    @DisplayName("a builder writes through the overload its property's type chooses, where that sorts first")
    void theChosenBuilderOverloadSortingFirst() {
      final var writer = Reflective.BEANS.writerFor(CharSequenceBuilt.class);
      assertEquals(CharSequence.class, Beans.writtenClass(writer, CharSequenceBuilt.class, "value"));
    }
  }

  @Test
  @DisplayName("a property a subclass passes its own variable to keeps that variable, and its bound")
  void aPropertyTypeKeepsTheSubclassVariable() {
    final var type = assertInstanceOf(ParameterizedType.class, Beans.resolvedPropertyType(Bounded.class, "value"));
    assertEquals(List.class, type.getRawType());
    assertEquals(Bounded.class.getTypeParameters()[0], type.getActualTypeArguments()[0]);
  }
}
