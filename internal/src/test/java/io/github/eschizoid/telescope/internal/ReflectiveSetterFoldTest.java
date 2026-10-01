package io.github.eschizoid.telescope.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which sides a composed setter fold may stand in for, as the write hints decide it. */
class ReflectiveSetterFoldTest {

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

  @Test
  @DisplayName("the unhinted singletons fold every class")
  void unhintedSidesFold() {
    assertTrue(Reflective.BEANS.foldsSetters(Both.class));
    assertTrue(Reflective.RECORDS.foldsSetters(Plain.class));
  }

  @Test
  @DisplayName("a per-class hint folds only when it names the setter writer")
  void perClassHintDecides() {
    assertFalse(
      Reflective.beansWithHints(Map.of(Both.class, Beans.builderWriter(Both.class)), null).foldsSetters(Both.class)
    );
    assertTrue(
      Reflective.beansWithHints(Map.of(Both.class, Beans.settersWriter(Both.class)), null).foldsSetters(Both.class)
    );
    assertTrue(
      Reflective.beansWithHints(Map.of(Both.class, Beans.builderWriter(Both.class)), null).foldsSetters(
        SettersOnly.class
      )
    );
  }

  @Test
  @DisplayName("a default strategy folds only where it picks the setter writer, and not where it cannot apply")
  void defaultStrategyDecides() {
    final var builders = Reflective.beansWithHints(Map.of(), cls -> Beans.builderWriter(cls));
    assertFalse(builders.foldsSetters(Both.class));
    assertFalse(builders.foldsSetters(SettersOnly.class), "no builder() to apply, so the construction refuses");
    assertTrue(builders.foldsSetters(Plain.class), "records are not written by bean strategies");
    assertTrue(Reflective.beansWithHints(Map.of(), cls -> Beans.settersWriter(cls)).foldsSetters(Both.class));
  }
}
