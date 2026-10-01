package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.BUILDER;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBean;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
  @DisplayName("with no hint, a bean with setters and a builder is built through its builder")
  void noHintBuildsThroughTheBuilder() {
    assertEquals("a [built]", Telescope.mapper(Source.class, Target.class).forward(new Source("a")).getName());
  }

  @Test
  @DisplayName("a per-class SETTERS hint writes a bean that also has a builder through its setters")
  void perClassSettersHintWins() {
    final var mapper = Telescope.mapper(Source.class, Target.class, writeBean(Target.class, SETTERS));
    assertEquals("a", mapper.forward(new Source("a")).getName());
  }

  @Test
  @DisplayName("a default SETTERS strategy writes a bean that also has a builder through its setters")
  void defaultSettersStrategyWins() {
    final var mapper = Telescope.mapper(Source.class, Target.class, writeBeans(SETTERS));
    assertEquals("a", mapper.forward(new Source("a")).getName());
  }

  @Test
  @DisplayName("a BUILDER hint keeps the builder write")
  void builderHintKeepsTheBuilder() {
    final var mapper = Telescope.mapper(Source.class, Target.class, writeBean(Target.class, BUILDER));
    assertEquals("a [built]", mapper.forward(new Source("a")).getName());
  }

  public record Holder(Target one, List<Target> many) {}

  public record SourceHolder(Source one, List<Source> many) {}

  /** A bean with setters and no builder, which a BUILDER default cannot write. */
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

  public record Mixed(Source one, Source two) {}

  public record MixedTarget(Target one, SettersOnly two) {}

  @Test
  @DisplayName("a hint on the source class decides how the backward direction builds it")
  void hintOnTheSourceSideDecidesBackward() {
    final var mapper = Telescope.mapper(Target.class, Source.class, writeBean(Target.class, SETTERS));
    final var target = new Target();
    target.setName("a");
    assertEquals("a", mapper.backward(new Source("a")).getName());
    assertEquals("a", mapper.forward(target).name());
  }

  @Test
  @DisplayName("a hinted bean nested in a record, and in a list, is written through its setters")
  void nestedAndListedHintedBeansUseTheHint() {
    final var mapper = Telescope.mapper(SourceHolder.class, Holder.class, writeBean(Target.class, SETTERS));
    final var out = mapper.forward(new SourceHolder(new Source("a"), List.of(new Source("b"))));
    assertEquals("a", out.one().getName());
    assertEquals("b", out.many().getFirst().getName());
  }

  @Test
  @DisplayName("a BUILDER default refuses a bean in the same tree that has no builder")
  void builderDefaultRefusesABeanWithoutABuilder() {
    final var thrown = assertThrows(IllegalStateException.class, () ->
      Telescope.mapper(Mixed.class, MixedTarget.class, writeBeans(BUILDER)).forward(
        new Mixed(new Source("a"), new Source("b"))
      )
    );
    assertTrue(thrown.getMessage().contains(SettersOnly.class.getName()), thrown::getMessage);
    assertTrue(thrown.getMessage().contains("builder()"), thrown::getMessage);
  }
}
