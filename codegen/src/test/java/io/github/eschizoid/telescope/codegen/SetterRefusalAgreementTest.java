package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.compileFully;
import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Locale;
import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds the compile-time verifier and mapper construction to one answer for a bean property whose
 * setter cannot take the value its getter returns: both refuse the mapper with the same message
 * wherever construction fills the property, on the target and, for a mapper that also runs
 * backward, on the source, and both accept it where construction never writes the property through
 * that setter.
 *
 * <p>Each case compiles its sources once with the verifier reporting warnings, which leaves the
 * classes to run, then builds the mapper those classes describe. Every case names its classes with
 * a prefix of its own, because one loader holds them all.
 */
class SetterRefusalAgreementTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";
  private static final String WARN = "-Atelescope.verify=warn";

  /**
   * A record source with an {@code int x}, and beans whose {@code x} is read as {@code int} or
   * {@code Number} behind setters of other types.
   */
  private static final String SHAPES = """
    package io.github.eschizoid.telescope.codegen;
    import static io.github.eschizoid.telescope.mapping.Mapping.*;
    import static io.github.eschizoid.telescope.mapping.WriteHint.*;
    import io.github.eschizoid.telescope.Telescope;
    import io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy;
    import java.util.List;
    import java.util.Map;
    import java.util.Optional;
    record @@Src(int x, String name) {}
    record @@NumSrc(Number x, String name) {}
    class @@Bean {
      private int x;
      private String name;
      public @@Bean() {}
      public int getX() { return x; }
      public void setX(String x) { this.x = Integer.parseInt(x); }
      public String getName() { return name; }
      public void setName(String name) { this.name = name; }
    }
    class @@Amb {
      private int x;
      private String name;
      public @@Amb() {}
      public int getX() { return x; }
      public void setX(String x) { this.x = Integer.parseInt(x); }
      public void setX(java.util.Date x) { this.x = -1; }
      public String getName() { return name; }
      public void setName(String name) { this.name = name; }
    }
    class @@Narrow {
      private Number x;
      private String name;
      public @@Narrow() {}
      public Number getX() { return x; }
      public void setX(Integer x) { this.x = x; }
      public String getName() { return name; }
      public void setName(String name) { this.name = name; }
    }
    class @@Wide {
      private long x;
      private String name;
      public @@Wide() {}
      public int getX() { return (int) x; }
      public void setX(long x) { this.x = x; }
      public String getName() { return name; }
      public void setName(String name) { this.name = name; }
    }
    class @@Built {
      private int x;
      private String name;
      public @@Built() {}
      public static Builder builder() { return new Builder(); }
      public static final class Builder {
        private int x;
        private String name;
        public Builder x(int x) { this.x = x; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public @@Built build() { final var b = new @@Built(); b.x = x; b.name = name; return b; }
      }
      public int getX() { return x; }
      public void setX(String x) { this.x = Integer.parseInt(x); }
      public String getName() { return name; }
      public void setName(String name) { this.name = name; }
    }
    class @@Ctor {
      private int x;
      private String name;
      public @@Ctor() {}
      public @@Ctor(int x, String name) { this.x = x; this.name = name; }
      public int getX() { return x; }
      public void setX(String x) { this.x = Integer.parseInt(x); }
      public String getName() { return name; }
      public void setName(String name) { this.name = name; }
    }
    class @@Ren {
      private int y;
      private String name;
      public @@Ren() {}
      public int getY() { return y; }
      public void setY(String y) { this.y = Integer.parseInt(y); }
      public String getName() { return name; }
      public void setName(String name) { this.name = name; }
    }
    record @@Outer(@@Src v) {}
    record @@OuterDto(@@Bean v) {}
    record @@ListOf(List<@@Src> v) {}
    record @@ListDto(List<@@Bean> v) {}
    record @@OptOf(Optional<@@Src> v) {}
    record @@MapOf(Map<String, @@Src> v) {}
    record @@MapDto(Map<String, @@Bean> v) {}
    """;

  /**
   * A record {@code Inner} whose own bridge writes the bean {@code InnerBean}'s {@code x} through a
   * transform producing the setter's type, and a pair of records holding one of each.
   */
  private static final String BRIDGED = """
    package io.github.eschizoid.telescope.codegen;
    import static io.github.eschizoid.telescope.mapping.Mapping.*;
    import io.github.eschizoid.telescope.Telescope;
    import io.github.eschizoid.telescope.annotations.Bridge;
    import io.github.eschizoid.telescope.annotations.Transform;
    @Bridge(value = @@InnerBean.class, transforms = @Transform(field = "x", using = @@Conv.class, method = "text"))
    record @@Inner(int x, String name) {}
    class @@InnerBean {
      private int x;
      private String name;
      public @@InnerBean() {}
      public int getX() { return x; }
      public void setX(String x) { this.x = Integer.parseInt(x); }
      public String getName() { return name; }
      public void setName(String name) { this.name = name; }
    }
    final class @@Conv {
      public static String text(final int i) { return Integer.toString(i); }
    }
    record @@Outer(@@Inner v) {}
    record @@OuterDto(@@InnerBean v) {}
    """;

  /**
   * The bean of {@link #BRIDGED} as a nested pair's target, with the pair's bridge declared on a
   * carrier rather than on either class.
   */
  private static final String CARRIED = """
    package io.github.eschizoid.telescope.codegen;
    import io.github.eschizoid.telescope.Telescope;
    import io.github.eschizoid.telescope.annotations.Bridge;
    import io.github.eschizoid.telescope.annotations.Transform;
    record @@Inner(int x, String name) {}
    class @@InnerBean {
      private int x;
      private String name;
      public @@InnerBean() {}
      public int getX() { return x; }
      public void setX(String x) { this.x = Integer.parseInt(x); }
      public String getName() { return name; }
      public void setName(String name) { this.name = name; }
    }
    final class @@Conv {
      public static String text(final int i) { return Integer.toString(i); }
    }
    @Bridge(
      source = @@Inner.class,
      target = @@InnerBean.class,
      transforms = @Transform(field = "x", using = @@Conv.class, method = "text")
    )
    final class @@Carrier {}
    record @@Outer(@@Inner v) {}
    record @@OuterDto(@@InnerBean v) {}
    """;

  private record Outcome(List<String> verifier, String construction) {}

  /**
   * Compiles {@code code} with the given processors and the verifier reporting warnings, defines
   * what it compiled, and calls the holder's {@code build()}: the verifier's setter reports, and
   * the message construction refused the mapper with, or null when it built.
   */
  private static Outcome outcome(
    final String prefix,
    final String code,
    final List<Processor> processors,
    final List<String> options
  ) {
    final var compilation = compileFully(
      processors,
      options,
      source(PACKAGE + "." + prefix + "Holder", code.replace("@@", prefix))
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var reported = compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
      .map(d -> d.getMessage(Locale.ROOT))
      .filter(m -> m.contains(" setter"))
      .toList();
    final var holder = compilation.define(MethodHandles.lookup()).get(PACKAGE + "." + prefix + "Holder");
    try {
      holder.getDeclaredMethod("build").invoke(null);
      return new Outcome(reported, null);
    } catch (final InvocationTargetException e) {
      return new Outcome(reported, e.getCause().getMessage());
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Outcome outcome(final String prefix, final String call) {
    return outcome(prefix, SHAPES + holder(call), List.of(new MapperVerifierProcessor()), List.of(WARN));
  }

  private static Outcome bridged(final String prefix, final String call) {
    return outcome(
      prefix,
      BRIDGED + holder(call),
      List.of(new BridgeProcessor(), new MapperVerifierProcessor()),
      List.of(WARN)
    );
  }

  /** A holder whose {@code build()} returns {@code call}. */
  private static String holder(final String call) {
    return "class @@Holder { static Object build() { return " + call + "; } }\n";
  }

  private static void assertBothRefuse(final Outcome outcome) {
    assertNotNull(outcome.construction(), "construction built the mapper");
    assertTrue(outcome.construction().contains(" setter"), outcome.construction());
    assertEquals(List.of(outcome.construction()), outcome.verifier());
  }

  private static void assertBothAccept(final Outcome outcome) {
    assertNull(outcome.construction(), outcome::construction);
    assertEquals(List.of(), outcome.verifier());
  }

  private static String cannotTake(final String owner, final String property, final String setter) {
    return PairingMessages.setterCannotTake(owner, property, setter + "(java.lang.String)", "int", "java.lang.String");
  }

  @Nested
  @DisplayName("A property filled through a setter that cannot take its type is refused by both, with one message")
  class Refused {

    @Test
    @DisplayName("an int getter with setX(String), on a mapper's target")
    void setterTakingAnotherType() {
      final var outcome = outcome("SrA", "Telescope.mapper(@@Src.class, @@Bean.class)");
      assertBothRefuse(outcome);
      assertEquals(cannotTake("SrABean", "x", "setX"), outcome.construction());
    }

    @Test
    @DisplayName("an int getter with setX(String) and setX(Date), neither more specific")
    void ambiguousOverloads() {
      final var outcome = outcome("SrB", "Telescope.mapper(@@Src.class, @@Amb.class)");
      assertBothRefuse(outcome);
      assertEquals(
        PairingMessages.ambiguousSetters(
          "SrBAmb",
          "x",
          "int",
          List.of("setX(java.lang.String)", "setX(java.util.Date)")
        ),
        outcome.construction()
      );
    }

    @Test
    @DisplayName("mapperForward onto the bean")
    void forwardOnlyOntoTheBean() {
      assertBothRefuse(outcome("SrC", "Telescope.mapperForward(@@Src.class, @@Bean.class)"));
    }

    @Test
    @DisplayName("Telescope.map onto the bean")
    void mapOntoTheBean() {
      assertBothRefuse(outcome("SrD", "Telescope.map(@@Src.class, @@Bean.class)"));
    }

    @Test
    @DisplayName("a two-way mapper from the bean, whose backward direction writes it")
    void twoWayFromTheBean() {
      assertBothRefuse(outcome("SrE", "Telescope.mapper(@@Bean.class, @@Src.class)"));
    }

    @Test
    @DisplayName("the bean as a nested pair's target")
    void nestedPair() {
      assertBothRefuse(outcome("SrF", "Telescope.mapper(@@Outer.class, @@OuterDto.class)"));
    }

    @Test
    @DisplayName("the bean as a list's element")
    void listElement() {
      assertBothRefuse(outcome("SrG", "Telescope.mapper(@@ListOf.class, @@ListDto.class)"));
    }

    @Test
    @DisplayName("the bean behind an Optional on the source side")
    void optionalElement() {
      assertBothRefuse(outcome("SrH", "Telescope.mapper(@@OptOf.class, @@OuterDto.class)"));
    }

    @Test
    @DisplayName("the bean as a map's value")
    void mapValue() {
      assertBothRefuse(outcome("SrI", "Telescope.mapper(@@MapOf.class, @@MapDto.class)"));
    }

    @Test
    @DisplayName("a row converting the property's value, whose result is still of the getter's type")
    void convertingRow() {
      assertBothRefuse(
        outcome("SrJ", "Telescope.mapper(@@Src.class, @@Bean.class, to(@@Src::x, @@Bean::getX, i -> i, i -> i))")
      );
    }

    @Test
    @DisplayName("a row renaming a source property onto the bean's")
    void renamingRow() {
      final var outcome = outcome("SrK", "Telescope.mapper(@@Src.class, @@Ren.class, to(@@Src::x, @@Ren::getY))");
      assertBothRefuse(outcome);
      assertEquals(cannotTake("SrKRen", "y", "setY"), outcome.construction());
    }

    @Test
    @DisplayName("a constant row on the property, beside a same-named source")
    void constantRowBesideASource() {
      assertBothRefuse(outcome("SrL", "Telescope.mapperForward(@@Src.class, @@Bean.class, constant(@@Bean::getX, 5))"));
    }

    @Test
    @DisplayName("a nested pair with a bridge of its own and a row keyed to it, which construction walks")
    void bridgedPairWithARow() {
      assertBothRefuse(
        bridged("SrM", "Telescope.mapper(@@Outer.class, @@OuterDto.class, to(@@Inner::name, @@InnerBean::getName))")
      );
    }

    @Test
    @DisplayName("under the default mode the verifier fails the build")
    void defaultModeFailsTheBuild() {
      final var compilation = compileFully(
        List.of(new MapperVerifierProcessor()),
        List.of(),
        source(
          PACKAGE + ".SrNHolder",
          (SHAPES + holder("Telescope.mapper(@@Src.class, @@Bean.class)")).replace("@@", "SrN")
        )
      );
      assertFalse(compilation.success());
      assertTrue(compilation.errorMessages().contains(cannotTake("SrNBean", "x", "setX")), compilation::errorMessages);
    }
  }

  @Nested
  @DisplayName("A property construction does not write through such a setter is accepted by both")
  class Accepted {

    @Test
    @DisplayName("mapperForward from the bean, which only reads it")
    void forwardOnlyFromTheBean() {
      assertBothAccept(outcome("SaA", "Telescope.mapperForward(@@Bean.class, @@Src.class)"));
    }

    @Test
    @DisplayName("a Number getter with setX(Integer), which some values fit")
    void narrowingSetter() {
      assertBothAccept(outcome("SaB", "Telescope.mapper(@@NumSrc.class, @@Narrow.class)"));
    }

    @Test
    @DisplayName("an int getter with setX(long), which every value fits")
    void wideningSetter() {
      assertBothAccept(outcome("SaC", "Telescope.mapper(@@Src.class, @@Wide.class)"));
    }

    @Test
    @DisplayName("a bean built through its builder")
    void builtThroughABuilder() {
      assertBothAccept(outcome("SaD", "Telescope.mapper(@@Src.class, @@Built.class)"));
    }

    @Test
    @DisplayName("a bean built through a public constructor whose parameters name every property")
    void builtThroughAConstructor() {
      assertBothAccept(
        outcome(
          "SaI",
          SHAPES + holder("Telescope.mapper(@@Src.class, @@Ctor.class)"),
          List.of(new MapperVerifierProcessor()),
          List.of(WARN, "-parameters")
        )
      );
    }

    @Test
    @DisplayName("a lenient mapperForward whose source has no property of the setter's name")
    void unfilledProperty() {
      assertBothAccept(outcome("SaE", "Telescope.mapperForward(@@Src.class, @@Ren.class)"));
    }

    @Test
    @DisplayName("a nested pair converted through a bridge of its own, by a two-way mapper")
    void nestedPairThroughItsBridge() {
      assertBothAccept(bridged("SaF", "Telescope.mapper(@@Outer.class, @@OuterDto.class)"));
    }

    @Test
    @DisplayName("a nested pair converted through a bridge of its own, by mapperForward")
    void nestedPairThroughItsBridgeForwardOnly() {
      assertBothAccept(bridged("SaG", "Telescope.mapperForward(@@Outer.class, @@OuterDto.class)"));
    }

    @Test
    @DisplayName("mapperForward given no rows, which converts through the pair's own bridge")
    void ownPairThroughItsBridge() {
      assertBothAccept(bridged("SaH", "Telescope.mapperForward(@@Inner.class, @@InnerBean.class)"));
    }
  }

  @Nested
  @DisplayName("A call whose writer construction alone decides is left to construction")
  class LeftToConstruction {

    @Test
    @DisplayName("a writeBean hint naming the bean")
    void writeHint() {
      final var outcome = outcome(
        "SlA",
        "Telescope.mapper(@@Src.class, @@Bean.class, writeBean(@@Bean.class, WriteStrategy.SETTERS))"
      );
      assertEquals(cannotTake("SlABean", "x", "setX"), outcome.construction());
      assertEquals(List.of(), outcome.verifier());
    }

    @Test
    @DisplayName("a nested pair a carrier's @Bridge names, which construction finds through its service registration")
    void pairACarrierNames() {
      // The in-memory harness registers no services, so only the verifier's answer is the one a
      // build with the carrier's registration on its class path sees.
      final var outcome = outcome(
        "SlB",
        CARRIED + holder("Telescope.mapper(@@Outer.class, @@OuterDto.class)"),
        List.of(new BridgeProcessor(), new MapperVerifierProcessor()),
        List.of(WARN)
      );
      assertEquals(List.of(), outcome.verifier());
    }
  }
}
