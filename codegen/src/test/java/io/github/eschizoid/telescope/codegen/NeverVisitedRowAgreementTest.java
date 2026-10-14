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
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds the compile-time verifier and mapper construction to one answer for a row keyed to a type
 * pair: both refuse it with the same message when the mapper never visits that pair, and both
 * accept it when the pair is reached through a nested property, a container, an {@code Optional}, a
 * map's values or an inherited getter.
 *
 * <p>Each case compiles its sources once with the verifier reporting warnings, which leaves the
 * classes to run, then builds the mapper those classes describe. Every case names its classes with
 * a prefix of its own, because one loader holds them all.
 */
class NeverVisitedRowAgreementTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  /**
   * A record source whose {@code inner} reaches {@code (Inner, Base)}. {@code Sub} extends {@code
   * Base}, so a row keyed to {@code (Inner, Sub)} names a pair nothing in the mapper reaches.
   */
  private static final String SHAPES = """
    package io.github.eschizoid.telescope.codegen;
    import static io.github.eschizoid.telescope.mapping.Mapping.*;
    import io.github.eschizoid.telescope.Telescope;
    enum @@Color { RED }
    enum @@Shade { RED }
    record @@Part(String v) {}
    record @@PartDto(String v) {}
    record @@Inner(String name, String label, @@Color color, @@Part part) {}
    class @@Base {
      private String name;
      public @@Base() {}
      public String getName() { return name; }
      public void setName(String name) { this.name = name; }
    }
    class @@Sub extends @@Base {
      private String label;
      private @@Shade shade;
      private @@PartDto part;
      public @@Sub() {}
      public String getLabel() { return label; }
      public void setLabel(String label) { this.label = label; }
      public @@Shade getShade() { return shade; }
      public void setShade(@@Shade shade) { this.shade = shade; }
      public @@PartDto getPart() { return part; }
      public void setPart(@@PartDto part) { this.part = part; }
    }
    record @@Outer(@@Inner inner, String note) {}
    record @@OuterDto(@@Base inner, String note) {}
    """;

  private record Outcome(List<String> verifier, String construction) {}

  /**
   * Compiles {@code code} with the verifier reporting warnings, defines what it compiled, and calls
   * the holder's {@code build()}: the verifier's never-visits reports, and the message construction
   * refused the mapper with, or null when it built.
   */
  private static Outcome outcome(final String prefix, final String code) {
    final var compilation = compileFully(
      List.of(new MapperVerifierProcessor()),
      List.of("-Atelescope.verify=warn"),
      source(PACKAGE + "." + prefix + "Holder", code.replace("@@", prefix))
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var reported = compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
      .map(d -> d.getMessage(Locale.ROOT))
      .filter(m -> m.contains("never visits"))
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

  /** A holder whose {@code build()} returns {@code call}. */
  private static String holder(final String call) {
    return "class @@Holder { static Object build() { return " + call + "; } }\n";
  }

  private static void assertBothRefuse(final Outcome outcome) {
    assertNotNull(outcome.construction(), "construction built the mapper");
    assertTrue(outcome.construction().contains("never visits"), outcome.construction());
    assertEquals(List.of(outcome.construction()), outcome.verifier());
  }

  private static void assertBothAccept(final Outcome outcome) {
    assertNull(outcome.construction(), outcome::construction);
    assertEquals(List.of(), outcome.verifier());
  }

  private static String name(final String prefix, final String simple) {
    return PACKAGE + "." + prefix + simple;
  }

  @Nested
  @DisplayName("Rows keyed to a pair the mapper never visits are refused by both, with one message")
  class Refused {

    @Test
    @DisplayName("to(src, tgt) onto a subclass of the property's declared class")
    void passThroughRowOntoASubclass() {
      final var outcome = outcome(
        "NvA",
        SHAPES + holder("Telescope.mapper(@@Outer.class, @@OuterDto.class, to(@@Inner::label, @@Sub::getName))")
      );
      assertBothRefuse(outcome);
      assertEquals(
        PairingMessages.rowsNeverVisited(
          name("NvA", "Outer"),
          name("NvA", "OuterDto"),
          List.of(PairingMessages.unvisitedPair(name("NvA", "Inner"), name("NvA", "Sub"), List.of("label -> getName")))
        ),
        outcome.construction()
      );
    }

    @Test
    @DisplayName("to(src, tgt, forward, backward)")
    void convertingRow() {
      assertBothRefuse(
        outcome(
          "NvB",
          SHAPES +
            holder(
              "Telescope.mapper(@@Outer.class, @@OuterDto.class, to(@@Inner::label, @@Sub::getName, s -> s, s -> s))"
            )
        )
      );
    }

    @Test
    @DisplayName("toOrElse(src, tgt, default)")
    void toOrElseRow() {
      assertBothRefuse(
        outcome(
          "NvC",
          SHAPES +
            holder("Telescope.mapper(@@Outer.class, @@OuterDto.class, toOrElse(@@Inner::label, @@Sub::getName, \"-\"))")
        )
      );
    }

    @Test
    @DisplayName("toOrElseGet(src, tgt, supplier)")
    void toOrElseGetRow() {
      assertBothRefuse(
        outcome(
          "NvD",
          SHAPES +
            holder(
              "Telescope.mapper(@@Outer.class, @@OuterDto.class, toOrElseGet(@@Inner::label, @@Sub::getName, () -> \"-\"))"
            )
        )
      );
    }

    @Test
    @DisplayName("enumTo(src, tgt, srcEnum, tgtEnum)")
    void enumToRow() {
      assertBothRefuse(
        outcome(
          "NvE",
          SHAPES +
            holder(
              "Telescope.mapper(@@Outer.class, @@OuterDto.class," +
                " enumTo(@@Inner::color, @@Sub::getShade, @@Color.class, @@Shade.class))"
            )
        )
      );
    }

    @Test
    @DisplayName("via(src, tgt, mapper)")
    void viaRow() {
      assertBothRefuse(
        outcome(
          "NvF",
          SHAPES +
            holder(
              "Telescope.mapper(@@Outer.class, @@OuterDto.class," +
                " via(@@Inner::part, @@Sub::getPart, Telescope.mapper(@@Part.class, @@PartDto.class)))"
            )
        )
      );
    }

    @Test
    @DisplayName("toOneWay(src, tgt, forward) on mapperForward")
    void toOneWayRow() {
      assertBothRefuse(
        outcome(
          "NvG",
          SHAPES +
            holder(
              "Telescope.mapperForward(@@Outer.class, @@OuterDto.class, toOneWay(@@Inner::label, @@Sub::getName, s -> s))"
            )
        )
      );
    }

    @Test
    @DisplayName("to(src, targetPath) whose source accessor reads a class other than the mapper's source")
    void accessorOntoTargetPath() {
      assertBothRefuse(
        outcome(
          "NvH",
          SHAPES +
            holder(
              "Telescope.mapper(@@Outer.class, @@OuterDto.class," +
                " to(@@Inner::label, Telescope.of(@@OuterDto.class).field(@@OuterDto::note)))"
            )
        )
      );
    }

    @Test
    @DisplayName("to(sourcePath, tgt) whose target accessor reads a class the mapper's target does not extend")
    void sourcePathOntoAccessor() {
      assertBothRefuse(
        outcome(
          "NvI",
          SHAPES +
            holder(
              "Telescope.mapper(@@Outer.class, @@OuterDto.class," +
                " to(Telescope.of(@@Outer.class).field(@@Outer::note), @@Sub::getLabel))"
            )
        )
      );
    }

    @Test
    @DisplayName("rows on two unvisited pairs are named in the order they were given")
    void twoUnvisitedPairsInOrder() {
      final var outcome = outcome(
        "NvJ",
        SHAPES +
          holder(
            "Telescope.mapper(@@Outer.class, @@OuterDto.class," +
              " to(@@Part::v, @@Sub::getLabel, s -> s, s -> s), to(@@Inner::label, @@Sub::getName)," +
              " drop(@@Part::v, @@Sub.class))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(
        PairingMessages.rowsNeverVisited(
          name("NvJ", "Outer"),
          name("NvJ", "OuterDto"),
          List.of(
            PairingMessages.unvisitedPair(name("NvJ", "Part"), name("NvJ", "Sub"), List.of("v -> getLabel")),
            PairingMessages.unvisitedPair(name("NvJ", "Inner"), name("NvJ", "Sub"), List.of("label -> getName"))
          )
        ),
        outcome.construction()
      );
    }

    @Test
    @DisplayName("a row below a property that a row with its own conversion claims")
    void rowBelowAConvertedProperty() {
      assertBothRefuse(
        outcome(
          "NvL",
          SHAPES +
            holder(
              "Telescope.mapper(@@Outer.class, @@OuterDto.class," +
                " to(@@Outer::inner, @@OuterDto::inner, i -> new @@Base(), b -> null)," +
                " to(@@Inner::label, @@Base::getName))"
            )
        )
      );
    }

    @Test
    @DisplayName("by default the verifier's report is a compile error")
    void defaultModeFailsTheBuild() {
      final var compilation = compileFully(
        List.of(new MapperVerifierProcessor()),
        List.of(),
        source(
          PACKAGE + ".NvKHolder",
          (
            SHAPES +
            holder("Telescope.mapper(@@Outer.class, @@OuterDto.class, to(@@Inner::label, @@Sub::getName))")
          ).replace("@@", "NvK")
        )
      );
      assertFalse(compilation.success());
      assertTrue(compilation.hasError("never visits"), compilation::errorMessages);
    }
  }

  @Nested
  @DisplayName("Rows keyed to a pair the mapper reaches are accepted by both")
  class Accepted {

    @Test
    @DisplayName("a nested property's declared class")
    void nestedPair() {
      assertBothAccept(
        outcome(
          "NvM",
          SHAPES + holder("Telescope.mapper(@@Outer.class, @@OuterDto.class, to(@@Inner::label, @@Base::getName))")
        )
      );
    }

    /** One element pair per way a container reaches it, so each row is reachable only one way. */
    private static final String CONTAINERS = """
      package io.github.eschizoid.telescope.codegen;
      import static io.github.eschizoid.telescope.mapping.Mapping.*;
      import io.github.eschizoid.telescope.Telescope;
      import java.util.*;
      record @@Item(String name, String label) {}
      record @@ItemDto(String name) {}
      record @@Maybe(String name, String label) {}
      record @@MaybeDto(String name) {}
      record @@Value(String name, String label) {}
      record @@ValueDto(String name) {}
      record @@Present(String name, String label) {}
      record @@PresentDto(String name) {}
      record @@Outer(List<@@Item> items, Optional<@@Maybe> maybe, Map<String, @@Value> byKey, Optional<@@Present> present) {}
      record @@OuterDto(List<@@ItemDto> items, @@MaybeDto maybe, Map<String, @@ValueDto> byKey, Optional<@@PresentDto> present) {}
      """;

    @Test
    @DisplayName("a list's element pair")
    void listElementPair() {
      assertBothAccept(
        outcome(
          "NvN",
          CONTAINERS + holder("Telescope.mapper(@@Outer.class, @@OuterDto.class, to(@@Item::label, @@ItemDto::name))")
        )
      );
    }

    @Test
    @DisplayName("an Optional source read into a nullable target")
    void optionalToNullablePair() {
      assertBothAccept(
        outcome(
          "NvO",
          CONTAINERS + holder("Telescope.mapper(@@Outer.class, @@OuterDto.class, to(@@Maybe::label, @@MaybeDto::name))")
        )
      );
    }

    @Test
    @DisplayName("a map's value pair")
    void mapValuePair() {
      assertBothAccept(
        outcome(
          "NvP",
          CONTAINERS + holder("Telescope.mapper(@@Outer.class, @@OuterDto.class, to(@@Value::label, @@ValueDto::name))")
        )
      );
    }

    @Test
    @DisplayName("an Optional's element pair")
    void optionalElementPair() {
      assertBothAccept(
        outcome(
          "NvQ",
          CONTAINERS +
            holder("Telescope.mapper(@@Outer.class, @@OuterDto.class, to(@@Present::label, @@PresentDto::name))")
        )
      );
    }

    @Test
    @DisplayName("a pair reached through a getter the target inherits")
    void inheritedPropertyPair() {
      assertBothAccept(
        outcome(
          "NvR",
          """
            package io.github.eschizoid.telescope.codegen;
            import static io.github.eschizoid.telescope.mapping.Mapping.*;
            import io.github.eschizoid.telescope.Telescope;
            record @@Inner(String name, String label) {}
            record @@InnerDto(String name) {}
            record @@Outer(@@Inner inner) {}
            class @@DtoBase {
              private @@InnerDto inner;
              public @@InnerDto getInner() { return inner; }
              public void setInner(@@InnerDto inner) { this.inner = inner; }
            }
            class @@OuterDto extends @@DtoBase { public @@OuterDto() {} }
            """ +
            holder("Telescope.mapper(@@Outer.class, @@OuterDto.class, to(@@Inner::label, @@InnerDto::name))")
        )
      );
    }

    @Test
    @DisplayName("a pair reached only through a property a to(src, tgt) row renames")
    void pairReachedThroughARenamingRow() {
      assertBothAccept(
        outcome(
          "NvS",
          """
            package io.github.eschizoid.telescope.codegen;
            import static io.github.eschizoid.telescope.mapping.Mapping.*;
            import io.github.eschizoid.telescope.Telescope;
            record @@Inner(String name, String label) {}
            record @@InnerDto(String name) {}
            record @@Outer(@@Inner inner) {}
            record @@OuterDto(@@InnerDto renamed) {}
            """ +
            holder(
              "Telescope.mapper(@@Outer.class, @@OuterDto.class," +
                " to(@@Outer::inner, @@OuterDto::renamed), to(@@Inner::label, @@InnerDto::name))"
            )
        )
      );
    }

    @Test
    @DisplayName("to(sourcePath, tgt) whose target accessor reads a class the mapper's target extends")
    void sourcePathOntoATargetSuperclass() {
      assertBothAccept(
        outcome(
          "NvT",
          """
            package io.github.eschizoid.telescope.codegen;
            import static io.github.eschizoid.telescope.mapping.Mapping.*;
            import io.github.eschizoid.telescope.Telescope;
            record @@Src(String note) {}
            class @@Base {
              private String name;
              public String getName() { return name; }
              public void setName(String name) { this.name = name; }
            }
            class @@Sub extends @@Base { public @@Sub() {} }
            """ +
            holder(
              "Telescope.mapperForward(@@Src.class, @@Sub.class, to(Telescope.of(@@Src.class).field(@@Src::note), @@Base::getName))"
            )
        )
      );
    }

    @Test
    @DisplayName("drop(src, target) on a pair the mapper never visits")
    void dropOnAnUnvisitedPair() {
      assertBothAccept(
        outcome(
          "NvU",
          SHAPES + holder("Telescope.mapper(@@Outer.class, @@OuterDto.class, drop(@@Inner::label, @@Sub.class))")
        )
      );
    }

    @Test
    @DisplayName("when(predicate, row), which is always applied at the mapper's own pair")
    void conditionalRow() {
      assertBothAccept(
        outcome(
          "NvV",
          SHAPES +
            holder(
              "Telescope.mapper(@@Outer.class, @@OuterDto.class," +
                " when(o -> true, to(@@Outer::note, Telescope.of(@@OuterDto.class).field(@@OuterDto::note))))"
            )
        )
      );
    }
  }

  @Test
  @DisplayName("a forward-only row on a strict factory is left to the refusal construction raises first")
  void forwardOnlyRowOnAStrictFactory() {
    final var outcome = outcome(
      "NvX",
      SHAPES +
        holder("Telescope.mapper(@@Outer.class, @@OuterDto.class, toOneWay(@@Inner::label, @@Sub::getName, s -> s))")
    );
    assertNotNull(outcome.construction(), "construction built the mapper");
    assertFalse(outcome.construction().contains("never visits"), outcome.construction());
    assertEquals(List.of(), outcome.verifier());
  }

  @Test
  @DisplayName("the verifier says nothing where a property is typed by a type variable it cannot bind")
  void silentOnATypeVariable() {
    final var outcome = outcome(
      "NvW",
      """
        package io.github.eschizoid.telescope.codegen;
        import static io.github.eschizoid.telescope.mapping.Mapping.*;
        import io.github.eschizoid.telescope.Telescope;
        record @@Inner(String name, String label) {}
        record @@InnerDto(String name) {}
        record @@Other(String name) {}
        class @@Box<T> {
          private T inner;
          public T getInner() { return inner; }
          public void setInner(T inner) { this.inner = inner; }
        }
        class @@InnerBox extends @@Box<@@Inner> { public @@InnerBox() {} }
        record @@OuterDto(@@InnerDto inner) {}
        """ +
        holder("Telescope.mapperForward(@@InnerBox.class, @@OuterDto.class, to(@@Inner::label, @@Other::name))")
    );
    assertEquals(List.of(), outcome.verifier());
  }
}
