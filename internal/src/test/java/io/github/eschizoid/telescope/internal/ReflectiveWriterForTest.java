package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The writer each side builds a bean with, as the write hints and the auto order decide it. A
 * composed build stands in for exactly this writer, so it is what decides how a composed leaf
 * builds that side.
 */
class ReflectiveWriterForTest {

  record Plain(String name) {}

  public static class Both {

    private String name;

    public Both() {}

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }

    public static Builder builder() {
      return new Builder();
    }

    public static final class Builder {

      private String name;

      public Builder name(final String name) {
        this.name = name;
        return this;
      }

      public Both build() {
        final var both = new Both();
        both.name = name;
        return both;
      }
    }
  }

  public static class SettersOnly {

    private String name;

    public SettersOnly() {}

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }
  }

  public static class CtorAndSetters {

    private String name;

    public CtorAndSetters() {}

    public CtorAndSetters(final String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }

    public void setName(final String name) {
      this.name = name;
    }
  }

  @Test
  @DisplayName("an unhinted bean gets the writer the auto order picks")
  void unhintedSidesGetTheAutoOrdersWriter() {
    assertInstanceOf(Beans.SettersWriter.class, Reflective.BEANS.writerFor(SettersOnly.class));
    assertInstanceOf(Beans.BuilderWriter.class, Reflective.BEANS.writerFor(Both.class), "the builder comes first");
    assertInstanceOf(
      Beans.ConstructorWriter.class,
      Reflective.BEANS.writerFor(CtorAndSetters.class),
      "the constructor comes before the setters"
    );
    assertNull(Reflective.RECORDS.writerFor(Plain.class), "a record is rebuilt through its canonical constructor");
  }

  @Test
  @DisplayName("hints that name nothing for a class leave it to the auto order")
  void hintsNamingNothingFallBackToTheAutoOrder() {
    final var other = Reflective.beansWithHints(
      Map.of(SettersOnly.class, Beans.settersWriter(SettersOnly.class)),
      null
    );
    assertInstanceOf(Beans.BuilderWriter.class, other.writerFor(Both.class), "no hint names Both");
    assertInstanceOf(Beans.ConstructorWriter.class, other.writerFor(CtorAndSetters.class));
    assertInstanceOf(Beans.SettersWriter.class, other.writerFor(SettersOnly.class));
  }

  @Test
  @DisplayName("a per-class hint decides the writer for the class it names")
  void perClassHintDecides() {
    final var builder = Beans.builderWriter(Both.class);
    final var setters = Beans.settersWriter(Both.class);
    assertSameWriter(builder, Reflective.beansWithHints(Map.of(Both.class, builder), null).writerFor(Both.class));
    assertSameWriter(setters, Reflective.beansWithHints(Map.of(Both.class, setters), null).writerFor(Both.class));
  }

  @Test
  @DisplayName("a default strategy decides the writer where it applies, and none where it cannot")
  void defaultStrategyDecides() {
    final var builders = Reflective.beansWithHints(Map.of(), cls -> Beans.builderWriter(cls));
    assertInstanceOf(Beans.BuilderWriter.class, builders.writerFor(Both.class));
    assertNull(builders.writerFor(SettersOnly.class), "no builder() to apply, so the construction refuses");
    assertNull(builders.writerFor(Plain.class), "records are not written by bean strategies");
    assertInstanceOf(
      Beans.SettersWriter.class,
      Reflective.beansWithHints(Map.of(), cls -> Beans.settersWriter(cls)).writerFor(Both.class)
    );
  }

  private static void assertSameWriter(final Beans.BeanWriter<?> expected, final Beans.BeanWriter<?> actual) {
    assertSame(expected, actual);
  }
}
