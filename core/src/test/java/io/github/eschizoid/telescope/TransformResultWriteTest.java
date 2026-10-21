package io.github.eschizoid.telescope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import io.github.eschizoid.telescope.mapping.Mapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A runtime row's result written through the member the property's type chooses, after a check of
 * its class. The suite also runs on the accessor substrate a native image uses, so both the
 * composed rebuild and its image counterpart check each result.
 */
class TransformResultWriteTest {

  public static class Src {

    private String value;

    public String getValue() {
      return value;
    }

    public void setValue(final String value) {
      this.value = value;
    }
  }

  public static class Tgt {

    private String value;

    public String getValue() {
      return value;
    }

    public void setValue(final String value) {
      this.value = value;
    }
  }

  /** A target written by a builder with an overload for the property's type and for Object. */
  public static final class Built {

    private final String value;

    private Built(final String value) {
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

      public Builder value(final String v) {
        this.value = "String:" + v;
        return this;
      }

      public Builder value(final Object v) {
        this.value = "Object:" + v;
        return this;
      }

      public Built build() {
        return new Built(value);
      }
    }
  }

  private static Src source(final String value) {
    final var src = new Src();
    src.setValue(value);
    return src;
  }

  @Test
  @DisplayName("a result of the class the setter takes is written")
  void aFittingResultIsWritten() {
    final var mapper = Telescope.mapperForward(
      Src.class,
      Tgt.class,
      Mapping.<Src, Tgt, String, Object>toOneWay(Src::getValue, Tgt::getValue, v -> v + "!")
    );
    assertEquals("a!", mapper.forward(source("a")).getValue());
  }

  @Test
  @DisplayName("a result of another class is refused naming the property, the class found and the class taken")
  void aResultOfAnotherClassIsRefused() {
    final var mapper = Telescope.mapperForward(
      Src.class,
      Tgt.class,
      Mapping.<Src, Tgt, String, Object>toOneWay(Src::getValue, Tgt::getValue, String::length)
    );
    final var refused = assertThrows(ClassCastException.class, () -> mapper.forward(source("abc")));
    assertEquals(
      PairingMessages.resultClassMismatch(Tgt.class.getTypeName(), "value", "java.lang.Integer", "java.lang.String"),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a backward result is checked against the source's setter")
  void aBackwardResultIsChecked() {
    final var mapper = Telescope.mapper(
      Src.class,
      Tgt.class,
      Mapping.<Src, Tgt, Object, String>to(Src::getValue, Tgt::getValue, String::valueOf, v -> v.length())
    );
    final var target = new Tgt();
    target.setValue("abc");
    final var refused = assertThrows(ClassCastException.class, () -> mapper.backward(target));
    assertEquals(
      PairingMessages.resultClassMismatch(Src.class.getTypeName(), "value", "java.lang.Integer", "java.lang.String"),
      refused.getMessage()
    );
  }

  @Test
  @DisplayName("a builder writes through the overload the property's type chooses")
  void aBuilderWritesThroughTheChosenOverload() {
    final var mapper = Telescope.mapperForward(
      Src.class,
      Built.class,
      Mapping.<Src, Built, String, Object>toOneWay(Src::getValue, Built::getValue, v -> v)
    );
    assertEquals("String:a", mapper.forward(source("a")).getValue());
  }
}
