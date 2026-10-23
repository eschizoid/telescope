package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.compileFully;
import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Holds the compile-time verifier and mapper construction to one answer for the properties a call
 * leaves to same-name matching, on every factory and beside any row, and for a row whose accessor
 * reads no property: construction refuses each with a message the verifier reports, and where
 * construction converts a pair some other way, through a bridge of its own or as a row keyed to it
 * says, the verifier says nothing about that pair's properties by name.
 *
 * <p>Each case compiles its sources once with the verifier reporting warnings, then builds the
 * mapper those classes describe. Every case names its classes with a prefix of its own, because one
 * loader holds them all.
 */
class VerifierSilenceAgreementTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  private static final String HEADER = """
    package io.github.eschizoid.telescope.codegen;
    import static io.github.eschizoid.telescope.mapping.Mapping.*;
    import io.github.eschizoid.telescope.Telescope;
    import io.github.eschizoid.telescope.annotations.*;
    import java.util.List;
    """;

  /** A list of records against a list of strings, beside a property that converts. */
  private static final String LISTS = """
    record @@X(String v) {}
    record @@Src(List<@@X> items, String note) {}
    record @@Tgt(List<String> items, String note, String stamp) {}
    """;

  private record Outcome(List<String> warnings, String construction) {}

  /**
   * Compiles {@code code} with {@code processors} and the verifier reporting warnings, defines what
   * it compiled, and calls the holder's {@code build()}: every warning, and the message
   * construction refused the mapper with, or null when it built.
   */
  private static Outcome outcome(final List<Processor> processors, final String prefix, final String code) {
    final var compilation = compileFully(
      processors,
      List.of("-Atelescope.verify=warn"),
      source(PACKAGE + "." + prefix + "Holder", (HEADER + code).replace("@@", prefix))
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var warnings = compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
      .map(d -> d.getMessage(Locale.ROOT))
      .filter(m -> !m.startsWith("auxiliary class"))
      .toList();
    final var holder = compilation.define(MethodHandles.lookup()).get(PACKAGE + "." + prefix + "Holder");
    try {
      holder.getDeclaredMethod("build").invoke(null);
      return new Outcome(warnings, null);
    } catch (final InvocationTargetException e) {
      return new Outcome(warnings, e.getCause().getMessage());
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Outcome verified(final String prefix, final String code) {
    return outcome(List.of(new MapperVerifierProcessor()), prefix, code);
  }

  private static Outcome bridged(final String prefix, final String code) {
    return outcome(List.of(new BridgeProcessor(), new MapperVerifierProcessor()), prefix, code);
  }

  /** A holder whose {@code build()} returns {@code call}. */
  private static String holder(final String call) {
    return "class @@Holder { static Object build() { return " + call + "; } }\n";
  }

  private static void assertBothRefuse(final Outcome outcome) {
    assertNotNull(outcome.construction(), "construction built the mapper");
    assertEquals(List.of(outcome.construction()), outcome.warnings());
  }

  private static void assertBothAccept(final Outcome outcome) {
    assertNull(outcome.construction(), outcome::construction);
    assertEquals(List.of(), outcome.warnings());
  }

  /** A pair renaming {@code n} onto {@code label}, beside a source {@code label} no row reads. */
  private static final String KEPT = """
    record @@Src(String n, String label) {}
    record @@Tgt(String label, String z) {}
    record @@Outer(@@Src in) {}
    record @@OuterDto(@@Tgt in) {}
    """;

  @Nested
  @DisplayName("A source property no row reads, whose same-named target a row of its pair fills, is refused by both")
  class KeptName {

    @Test
    @DisplayName("on the mapper's own pair, beside a compute row")
    void besideACompute() {
      assertBothRefuse(
        verified(
          "VsN",
          KEPT +
            holder(
              "Telescope.mapper(@@Src.class, @@Tgt.class, to(@@Src::n, @@Tgt::label), compute(@@Tgt::z, () -> \"z\"))"
            )
        )
      );
    }

    @Test
    @DisplayName("on a nested pair with a row of its own")
    void onANestedPair() {
      assertBothRefuse(
        verified("VsO", KEPT + holder("Telescope.mapper(@@Outer.class, @@OuterDto.class, to(@@Src::n, @@Tgt::label))"))
      );
    }

    // The kept property's type differs from its same-named target's: the row claims the target,
    // so the one refusal is the kept-name one, with no complaint about the two types.
    @Test
    @DisplayName("on a nested pair whose kept property is of another type than its same-named target")
    void ofAnotherType() {
      assertBothRefuse(
        verified(
          "VsK",
          """
            record @@Inner(String a, Integer b) {}
            record @@InnerDto(String b) {}
            record @@Outer(@@Inner inner) {}
            record @@OuterDto(@@InnerDto inner) {}
            """ +
            holder("Telescope.mapper(@@Outer.class, @@OuterDto.class, to(@@Inner::a, @@InnerDto::b))")
        )
      );
    }

    @Test
    @DisplayName("not on a nested pair whose rows swap two properties, since each is read")
    void notASwap() {
      assertBothAccept(
        verified(
          "VsQ",
          """
            record @@Inner(String n, String label) {}
            record @@InnerDto(String n, String label) {}
            record @@Outer(@@Inner in) {}
            record @@OuterDto(@@InnerDto in) {}
            """ +
            holder(
              "Telescope.mapper(@@Outer.class, @@OuterDto.class, to(@@Inner::n, @@InnerDto::label)," +
                " to(@@Inner::label, @@InnerDto::n))"
            )
        )
      );
    }

    @Test
    @DisplayName("not on a mapperForward call, which leaves it unread")
    void notForwardOnly() {
      assertBothAccept(
        verified(
          "VsP",
          KEPT + holder("Telescope.mapperForward(@@Outer.class, @@OuterDto.class, to(@@Src::n, @@Tgt::label))")
        )
      );
    }
  }

  @Nested
  @DisplayName("A property left to same-name matching that construction refuses is refused by both")
  class SameNamed {

    @Test
    @DisplayName("on a mapperForward call given no rows")
    void mapperForwardWithNoRows() {
      final var outcome = verified("VsA", LISTS + holder("Telescope.mapperForward(@@Src.class, @@Tgt.class)"));
      assertBothRefuse(outcome);
      assertTrue(outcome.construction().contains("'items[*]'"), outcome.construction());
    }

    @Test
    @DisplayName("on a mapperForward call given a row")
    void mapperForwardWithARow() {
      assertBothRefuse(
        verified(
          "VsB",
          LISTS + holder("Telescope.mapperForward(@@Src.class, @@Tgt.class, to(@@Src::note, @@Tgt::stamp))")
        )
      );
    }

    @Test
    @DisplayName("beside a constant row")
    void besideAConstant() {
      assertBothRefuse(
        verified("VsC", LISTS + holder("Telescope.mapper(@@Src.class, @@Tgt.class, constant(@@Tgt::stamp, \"s\"))"))
      );
    }

    @Test
    @DisplayName("beside a compute row on a mapperForward call")
    void besideACompute() {
      assertBothRefuse(
        verified(
          "VsD",
          LISTS + holder("Telescope.mapperForward(@@Src.class, @@Tgt.class, compute(@@Tgt::stamp, () -> \"s\"))")
        )
      );
    }
  }

  @Nested
  @DisplayName("A row whose accessor reads no property is refused by both")
  class ReadingNoProperty {

    @Test
    @DisplayName("a bean target accessor that is no getter")
    void beanTargetWithNoGetter() {
      final var outcome = verified(
        "VsE",
        """
          class @@Bean {
            private String code;
            public @@Bean() {}
            public String code() { return code; }
            public String getName() { return null; }
            public void setName(String n) {}
          }
          record @@Rec(String name, String code) {}
          """ +
          holder("Telescope.mapperForward(@@Rec.class, @@Bean.class, to(@@Rec::code, @@Bean::code))")
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.noGetter("code", PACKAGE + ".VsEBean"), outcome.construction());
    }

    @Test
    @DisplayName("a record source accessor that is no component")
    void recordSourceWithNoComponent() {
      final var outcome = verified(
        "VsF",
        """
          record @@Rec(String name) { public String code() { return name; } }
          record @@Dto(String name, String code) {}
          """ +
          holder("Telescope.mapperForward(@@Rec.class, @@Dto.class, to(@@Rec::code, @@Dto::code))")
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.noComponent("code", PACKAGE + ".VsFRec", List.of("name")), outcome.construction());
    }

    @Test
    @DisplayName("a drop of a record accessor that is no component")
    void dropOfNoComponent() {
      assertBothRefuse(
        verified(
          "VsG",
          """
            record @@Rec(String name) { public String code() { return name; } }
            record @@Dto(String name) {}
            """ +
            holder("Telescope.mapper(@@Rec.class, @@Dto.class, drop(@@Rec::code))")
        )
      );
    }

    @Test
    @DisplayName("a row keyed to a nested pair the mapper visits")
    void nestedRow() {
      final var outcome = verified(
        "VsH",
        """
          record @@Inner(String name) { public String code() { return name; } }
          record @@InnerDto(String name, String code) {}
          record @@Outer(@@Inner inner) {}
          record @@OuterDto(@@InnerDto inner) {}
          """ +
          holder("Telescope.mapperForward(@@Outer.class, @@OuterDto.class, to(@@Inner::code, @@InnerDto::code))")
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.noComponent("code", PACKAGE + ".VsHInner", List.of("name")), outcome.construction());
    }
  }

  @Nested
  @DisplayName("A pair construction converts some other way than by name is accepted by both")
  class ConvertedOtherwise {

    /** Two records a bridge pairs crosswise, whose same-named components do not pair. */
    private static final String CROSSWISE = """
      @Bridge(value = @@InnerDto.class, renames = { @Rename(source = "x", target = "y"), @Rename(source = "y", target = "x") })
      record @@Inner(Integer x, String y) {}
      record @@InnerDto(String x, Integer y) {}
      record @@Outer(@@Inner inner) {}
      record @@OuterDto(@@InnerDto inner) {}
      """;

    @Test
    @DisplayName("a nested pair converted through its own bridge")
    void nestedPairThroughItsBridge() {
      assertBothAccept(bridged("VsI", CROSSWISE + holder("Telescope.mapper(@@Outer.class, @@OuterDto.class)")));
    }

    @Test
    @DisplayName("a mapperForward call given no rows, converted through its own pair's bridge")
    void rootThroughItsBridge() {
      assertBothAccept(bridged("VsJ", CROSSWISE + holder("Telescope.mapperForward(@@Inner.class, @@InnerDto.class)")));
    }
  }

  /**
   * Construction converts a nested pair through its own bridge unless something the mapper is given
   * reaches below the properties that bridge walks itself, and then visits nothing below the pair.
   * The verifier reads the properties a bridge in the same compilation walks from its
   * {@code @Bridge}, and asks the same shared question, so a row keyed to a pair reachable only
   * through a property the bridge drops is refused by both.
   */
  @Test
  @DisplayName("a row reachable only through a property a nested pair's own bridge drops is refused by both")
  void rowBelowABridgedPropertyIsRefusedByBoth() {
    final var outcome = bridged(
      "VsL",
      """
        record @@Part(String v) {}
        record @@PartDto(String v) {}
        @Bridge(value = @@InnerDto.class, drops = "part", lenient = true)
        record @@Inner(String name, @@Part part) {}
        record @@InnerDto(String name, @@PartDto part) {}
        record @@Outer(@@Inner inner) {}
        record @@OuterDto(@@InnerDto inner) {}
        """ +
        holder("Telescope.mapperForward(@@Outer.class, @@OuterDto.class, to(@@Part::v, @@PartDto::v))")
    );
    assertNotNull(outcome.construction(), "construction built the mapper");
    assertTrue(outcome.construction().contains("never visits"), outcome.construction());
    assertEquals(List.of(outcome.construction()), outcome.warnings());
  }
}
