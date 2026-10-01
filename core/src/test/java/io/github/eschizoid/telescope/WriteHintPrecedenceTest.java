package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.BUILDER;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBean;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A write hint decides how a bean target is built even when the bean could also be built the
 * default way. The fixture has a no-arg constructor, setters and a builder, and only its builder
 * marks what it builds, so the writer that ran is visible in the result.
 */
class WriteHintPrecedenceTest {

  public record Source(String name) {}

  public static class Target {

    private String name;

    public Target() {}

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

      public Target build() {
        final var target = new Target();
        target.name = name + " [built]";
        return target;
      }
    }
  }

  @Test
  @DisplayName("with no hint, a bean with setters is written through its setters")
  void noHintWritesThroughSetters() {
    assertEquals("a", Telescope.mapper(Source.class, Target.class).forward(new Source("a")).getName());
  }

  @Test
  @DisplayName("a per-class BUILDER hint builds a bean that also has setters through its builder")
  void perClassBuilderHintWins() {
    final var mapper = Telescope.mapper(Source.class, Target.class, writeBean(Target.class, BUILDER));
    assertEquals("a [built]", mapper.forward(new Source("a")).getName());
  }

  @Test
  @DisplayName("a default BUILDER strategy builds a bean that also has setters through its builder")
  void defaultBuilderStrategyWins() {
    final var mapper = Telescope.mapper(Source.class, Target.class, writeBeans(BUILDER));
    assertEquals("a [built]", mapper.forward(new Source("a")).getName());
  }

  @Test
  @DisplayName("a SETTERS hint keeps the setter write")
  void settersHintKeepsSetters() {
    final var mapper = Telescope.mapper(Source.class, Target.class, writeBean(Target.class, SETTERS));
    assertEquals("a", mapper.forward(new Source("a")).getName());
  }
}
