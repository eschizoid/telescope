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
    import io.github.eschizoid.telescope.conversion.ForwardMapper;
    import io.github.eschizoid.telescope.conversion.Mapper;
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
      record @@Inner(Integer x, String y) {
        @Bridge
        static final Mapper<@@Inner, @@InnerDto> MAPPER = Telescope.mapper(
          @@Inner.class,
          @@InnerDto.class,
          to(@@Inner::x, @@InnerDto::y),
          to(@@Inner::y, @@InnerDto::x)
        );
      }
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
   * {@code @Bridge} field's rows, and asks the same shared question, so a row keyed to a pair
   * reachable only through a property the bridge drops is refused by both.
   */
  @Test
  @DisplayName("a row reachable only through a property a nested pair's own bridge drops is refused by both")
  void rowBelowABridgedPropertyIsRefusedByBoth() {
    final var outcome = bridged(
      "VsL",
      """
        record @@Part(String v) {}
        record @@PartDto(String v) {}
        record @@Inner(String name, @@Part part) {
          @Bridge
          static final ForwardMapper<@@Inner, @@InnerDto> MAPPER = Telescope.mapperForward(
            @@Inner.class,
            @@InnerDto.class,
            drop(@@Inner::part)
          );
        }
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

  /** A pair with one source property and two targets, at the mapper's pair and nested below one. */
  private static final String FANNED = """
    record @@Src(String n) {}
    record @@Tgt(String a, String n) {}
    record @@Outer(@@Src in) {}
    record @@OuterDto(@@Tgt in) {}
    """;

  /** A pair whose one source property has a namesake target, at the mapper's pair and nested. */
  private static final String NAMESAKES = """
    record @@Src(String n) {}
    record @@Tgt(String n) {}
    record @@Outer(@@Src in) {}
    record @@OuterDto(@@Tgt in) {}
    """;

  @Nested
  @DisplayName("A source property claimed twice by rows of its pair is refused by both")
  class ClaimedTwice {

    @Test
    @DisplayName("written into two targets of a two-way mapper, on the mapper's own pair")
    void fanOutOnTheOwnPair() {
      final var outcome = verified(
        "VwA",
        FANNED + holder("Telescope.mapper(@@Src.class, @@Tgt.class, to(@@Src::n, @@Tgt::n), to(@@Src::n, @@Tgt::a))")
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.fanOutBothWays("VwASrc", "VwATgt", "n", List.of("n", "a")), outcome.construction());
    }

    @Test
    @DisplayName("written into two targets of a two-way mapper, on a nested pair")
    void fanOutOnANestedPair() {
      final var outcome = verified(
        "VwB",
        FANNED +
          holder("Telescope.mapper(@@Outer.class, @@OuterDto.class, to(@@Src::n, @@Tgt::a), to(@@Src::n, @@Tgt::n))")
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.fanOutBothWays("VwBSrc", "VwBTgt", "n", List.of("a", "n")), outcome.construction());
    }

    @Test
    @DisplayName("not written into two targets of a mapperForward call, on the own pair or a nested one")
    void fanOutForwardOnly() {
      assertBothAccept(
        verified(
          "VwC",
          FANNED +
            holder("Telescope.mapperForward(@@Src.class, @@Tgt.class, to(@@Src::n, @@Tgt::n), to(@@Src::n, @@Tgt::a))")
        )
      );
      assertBothAccept(
        verified(
          "VwD",
          FANNED +
            holder(
              "Telescope.mapperForward(@@Outer.class, @@OuterDto.class, to(@@Src::n, @@Tgt::a), to(@@Src::n, @@Tgt::n))"
            )
        )
      );
    }

    @Test
    @DisplayName("dropped and then written, on the mapper's own pair")
    void dropThenWrite() {
      final var outcome = verified(
        "VwE",
        NAMESAKES + holder("Telescope.mapper(@@Src.class, @@Tgt.class, drop(@@Src::n), to(@@Src::n, @@Tgt::n))")
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.droppedAndWritten("VwESrc", "VwETgt", "n"), outcome.construction());
    }

    @Test
    @DisplayName("written and then dropped by a mapperForward call, on the mapper's own pair")
    void writeThenDropForwardOnly() {
      final var outcome = verified(
        "VwF",
        FANNED + holder("Telescope.mapperForward(@@Src.class, @@Tgt.class, to(@@Src::n, @@Tgt::a), drop(@@Src::n))")
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.droppedAndWritten("VwFSrc", "VwFTgt", "n"), outcome.construction());
    }

    @Test
    @DisplayName("written and then dropped, on a nested pair")
    void writeThenDropOnANestedPair() {
      final var outcome = verified(
        "VwG",
        NAMESAKES +
          holder(
            "Telescope.mapper(@@Outer.class, @@OuterDto.class, to(@@Src::n, @@Tgt::n), drop(@@Src::n, @@Tgt.class))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.droppedAndWritten("VwGSrc", "VwGTgt", "n"), outcome.construction());
    }

    @Test
    @DisplayName("written into three targets, each named once")
    void threeTargets() {
      final var outcome = verified(
        "VwH",
        """
          record @@Src(String n) {}
          record @@Tgt(String a, String b, String c) {}
          """ +
          holder(
            "Telescope.mapper(@@Src.class, @@Tgt.class, to(@@Src::n, @@Tgt::a), to(@@Src::n, @@Tgt::b)," +
              " to(@@Src::n, @@Tgt::c))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(
        PairingMessages.fanOutBothWays("VwHSrc", "VwHTgt", "n", List.of("a", "b", "c")),
        outcome.construction()
      );
    }

    @Test
    @DisplayName("written through two source paths one field hop long")
    void twoSourcePaths() {
      final var outcome = verified(
        "VwI",
        FANNED +
          holder(
            "Telescope.mapper(@@Src.class, @@Tgt.class, to(Telescope.of(@@Src.class).field(@@Src::n), @@Tgt::n)," +
              " to(Telescope.of(@@Src.class).field(@@Src::n), @@Tgt::a))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.fanOutBothWays("VwISrc", "VwITgt", "n", List.of("n", "a")), outcome.construction());
    }

    @Test
    @DisplayName("written through two target paths")
    void twoTargetPaths() {
      final var outcome = verified(
        "VwJ",
        FANNED +
          holder(
            "Telescope.mapper(@@Src.class, @@Tgt.class, to(@@Src::n, Telescope.of(@@Tgt.class).field(@@Tgt::n))," +
              " to(@@Src::n, Telescope.of(@@Tgt.class).field(@@Tgt::a)))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.fanOutBothWays("VwJSrc", "VwJTgt", "n", List.of("n", "a")), outcome.construction());
    }

    @Test
    @DisplayName("written through an accessor row and a source path")
    void accessorAndSourcePath() {
      final var outcome = verified(
        "VwK",
        FANNED +
          holder(
            "Telescope.mapper(@@Src.class, @@Tgt.class, to(@@Src::n, @@Tgt::a)," +
              " to(Telescope.of(@@Src.class).field(@@Src::n), @@Tgt::n))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.fanOutBothWays("VwKSrc", "VwKTgt", "n", List.of("a", "n")), outcome.construction());
    }

    @Test
    @DisplayName("one source path of two steps written into two targets")
    void pathOfTwoStepsInTwoTargets() {
      final var outcome = verified(
        "VwX",
        """
          record @@Mid(String n) {}
          record @@Src(@@Mid m) {}
          record @@Tgt(String a, String b) {}
          """ +
          holder(
            "Telescope.mapper(@@Src.class, @@Tgt.class," +
              " to(Telescope.of(@@Src.class).field(@@Src::m).field(@@Mid::n), @@Tgt::a)," +
              " to(Telescope.of(@@Src.class).field(@@Src::m).field(@@Mid::n), @@Tgt::b))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(
        PairingMessages.fanOutBothWays("VwXSrc", "VwXTgt", "m.n", List.of("a", "b")),
        outcome.construction()
      );
    }

    @Test
    @DisplayName("two source paths through one property, each into a target of its own")
    void twoPathsThroughOneProperty() {
      assertBothAccept(
        verified(
          "VwY",
          """
            record @@Mid(String n, String k) {}
            record @@Src(@@Mid m) {}
            record @@Tgt(String a, String b) {}
            """ +
            holder(
              "Telescope.mapper(@@Src.class, @@Tgt.class," +
                " to(Telescope.of(@@Src.class).field(@@Src::m).field(@@Mid::n), @@Tgt::a)," +
                " to(Telescope.of(@@Src.class).field(@@Src::m).field(@@Mid::k), @@Tgt::b))"
            )
        )
      );
    }

    @Test
    @DisplayName("dropped and read through by a source path of two steps")
    void dropThenPathThroughIt() {
      final var outcome = verified(
        "VwZ",
        """
          record @@Mid(String n) {}
          record @@Src(@@Mid m) {}
          record @@Tgt(String a) {}
          """ +
          holder(
            "Telescope.mapperForward(@@Src.class, @@Tgt.class, drop(@@Src::m)," +
              " to(Telescope.of(@@Src.class).field(@@Src::m).field(@@Mid::n), @@Tgt::a))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.droppedAndReadThrough("VwZSrc", "VwZTgt", "m", "m.n"), outcome.construction());
    }

    @Test
    @DisplayName("dropped and written through a target path")
    void dropThenTargetPath() {
      final var outcome = verified(
        "VwM",
        NAMESAKES +
          holder(
            "Telescope.mapper(@@Src.class, @@Tgt.class, drop(@@Src::n), to(@@Src::n, Telescope.of(@@Tgt.class).field(@@Tgt::n)))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.droppedAndWritten("VwMSrc", "VwMTgt", "n"), outcome.construction());
    }

    @Test
    @DisplayName("written through a source path and then dropped")
    void sourcePathThenDrop() {
      final var outcome = verified(
        "VwN",
        NAMESAKES +
          holder(
            "Telescope.mapper(@@Src.class, @@Tgt.class, to(Telescope.of(@@Src.class).field(@@Src::n), @@Tgt::n), drop(@@Src::n))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.droppedAndWritten("VwNSrc", "VwNTgt", "n"), outcome.construction());
    }

    @Test
    @DisplayName("written into two targets of a pair of sealed cases, by rows keyed to the interfaces")
    void fanOutOnASealedCase() {
      final var outcome = verified(
        "VwO",
        """
          sealed interface @@Sh permits @@Sq { String n(); }
          record @@Sq(String n) implements @@Sh {}
          sealed interface @@ShD permits @@SqD { String a(); String b(); }
          record @@SqD(String a, String b) implements @@ShD {}
          """ +
          holder(
            "Telescope.mapper(@@Sh.class, @@ShD.class, io.github.eschizoid.telescope.mapping.Cases.cases(@@Sq.class," +
              " @@SqD.class), to(@@Sh::n, @@ShD::a), to(@@Sh::n, @@ShD::b))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.fanOutBothWays("VwOSq", "VwOSqD", "n", List.of("a", "b")), outcome.construction());
    }

    @Test
    @DisplayName("written into two targets of a pair met as the elements of a list")
    void fanOutInAList() {
      final var outcome = verified(
        "VwP",
        """
          record @@Src(String n) {}
          record @@Tgt(String a, String n) {}
          record @@Outer(List<@@Src> in) {}
          record @@OuterDto(List<@@Tgt> in) {}
          """ +
          holder("Telescope.mapper(@@Outer.class, @@OuterDto.class, to(@@Src::n, @@Tgt::a), to(@@Src::n, @@Tgt::n))")
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.fanOutBothWays("VwPSrc", "VwPTgt", "n", List.of("a", "n")), outcome.construction());
    }

    @Test
    @DisplayName("written into two targets of a pair of sealed cases, by rows keyed to the cases")
    void fanOutKeyedToASealedCase() {
      final var outcome = verified(
        "VwS",
        """
          sealed interface @@Sh permits @@Sq { String n(); }
          record @@Sq(String n) implements @@Sh {}
          sealed interface @@ShD permits @@SqD { String a(); String b(); }
          record @@SqD(String a, String b) implements @@ShD {}
          """ +
          holder(
            "Telescope.mapper(@@Sh.class, @@ShD.class, io.github.eschizoid.telescope.mapping.Cases.cases(@@Sq.class," +
              " @@SqD.class), to(@@Sq::n, @@SqD::b), to(@@Sq::n, @@SqD::a))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.fanOutBothWays("VwSSq", "VwSSqD", "n", List.of("b", "a")), outcome.construction());
    }

    @Test
    @DisplayName("dropped and written by rows keyed to a pair of sealed cases")
    void dropAndWriteKeyedToASealedCase() {
      final var outcome = verified(
        "VwT",
        """
          sealed interface @@Sh permits @@Sq { String n(); }
          record @@Sq(String n) implements @@Sh {}
          sealed interface @@ShD permits @@SqD { String n(); }
          record @@SqD(String n) implements @@ShD {}
          """ +
          holder(
            "Telescope.mapper(@@Sh.class, @@ShD.class, io.github.eschizoid.telescope.mapping.Cases.cases(@@Sq.class," +
              " @@SqD.class), drop(@@Sq::n, @@SqD.class), to(@@Sq::n, @@SqD::n))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.droppedAndWritten("VwTSq", "VwTSqD", "n"), outcome.construction());
    }

    @Test
    @DisplayName("not for a toOneWay row of a two-way mapper, which construction refuses for itself")
    void toOneWayIsLeftToConstruction() {
      final var outcome = verified(
        "VwQ",
        FANNED +
          holder(
            "Telescope.mapper(@@Src.class, @@Tgt.class, to(@@Src::n, @@Tgt::n), toOneWay(@@Src::n, @@Tgt::a, String::trim))"
          )
      );
      assertNotNull(outcome.construction(), "construction built the mapper");
      assertTrue(outcome.construction().contains("cannot accept a Mapping.toOneWay(...) row"), outcome.construction());
      assertEquals(List.of(), outcome.warnings());
    }

    @Test
    @DisplayName("not for an accessor row under when(...), which construction refuses for itself")
    void gatedAccessorRowIsLeftToConstruction() {
      final var outcome = verified(
        "VwR",
        FANNED +
          holder(
            "Telescope.mapper(@@Src.class, @@Tgt.class, to(@@Src::n, @@Tgt::n), when(s -> true, to(@@Src::n, @@Tgt::a)))"
          )
      );
      assertNotNull(outcome.construction(), "construction built the mapper");
      assertTrue(
        outcome.construction().contains("Mapping.when(...) wraps only telescope-based rows"),
        outcome.construction()
      );
      assertEquals(List.of(), outcome.warnings());
    }
  }

  /** A source holding {@code a} and a target holding {@code a, b, c}, at the mapper's pair. */
  private static final String B_UNPAIRED = """
    record @@Src(String a) {}
    record @@Tgt(String a, String b, String c) {}
    """;

  /** As {@link #B_UNPAIRED}, with a source {@code x} for a row to read into {@code c}. */
  private static final String B_UNPAIRED_X = """
    record @@Src(String a, String x) {}
    record @@Tgt(String a, String b, String c) {}
    """;

  /** A source holding {@code a, x, y} and a target holding {@code a, c}, at the mapper's pair. */
  private static final String Y_UNPAIRED_X = """
    record @@Src(String a, String x, String y) {}
    record @@Tgt(String a, String c) {}
    """;

  /** Every property of the pair paired with one, once a row reads {@code x} into {@code c}. */
  private static final String X_INTO_C = """
    record @@Src(String a, String x) {}
    record @@Tgt(String a, String c) {}
    """;

  /** The refusal of target {@code b} of the pair prefixed {@code prefix}, which nothing fills. */
  private static String noSourceForB(final String prefix) {
    return PairingMessages.noSameNameSource(prefix + "Src", prefix + "Tgt", "field", "field", "b");
  }

  /** The refusal of source {@code y} of the pair prefixed {@code prefix}, which nothing reads. */
  private static String noTargetForY(final String prefix) {
    return PairingMessages.noSameNameTarget(prefix + "Src", prefix + "Tgt", "field", "field", "y");
  }

  @Nested
  @DisplayName("A property nothing pairs with, beside a row given a path, a constant or a compute, is refused by both")
  class UnpairedBesideARow {

    private void assertTargetBRefused(final String prefix, final String records, final String row) {
      final var outcome = verified(prefix, records + holder("Telescope.mapper(@@Src.class, @@Tgt.class, " + row + ")"));
      assertBothRefuse(outcome);
      assertEquals(noSourceForB(prefix), outcome.construction());
    }

    private void assertSourceYRefused(final String prefix, final String records, final String row) {
      final var outcome = verified(prefix, records + holder("Telescope.mapper(@@Src.class, @@Tgt.class, " + row + ")"));
      assertBothRefuse(outcome);
      assertEquals(noTargetForY(prefix), outcome.construction());
    }

    @Test
    @DisplayName("a target property beside a constant row")
    void targetBesideAConstant() {
      assertTargetBRefused("VuA", B_UNPAIRED, "constant(@@Tgt::c, \"k\")");
    }

    @Test
    @DisplayName("a target property beside a compute row")
    void targetBesideACompute() {
      assertTargetBRefused("VuB", B_UNPAIRED, "compute(@@Tgt::c, () -> \"k\")");
    }

    @Test
    @DisplayName("a target property beside a constant row given a path")
    void targetBesideAConstantPath() {
      assertTargetBRefused("VuC", B_UNPAIRED, "constant(Telescope.of(@@Tgt.class).field(@@Tgt::c), \"k\")");
    }

    @Test
    @DisplayName("a target property beside a compute row given a path")
    void targetBesideAComputePath() {
      assertTargetBRefused("VuD", B_UNPAIRED, "compute(Telescope.of(@@Tgt.class).field(@@Tgt::c), () -> \"k\")");
    }

    @Test
    @DisplayName("a target property beside a row writing through a target path")
    void targetBesideATargetPath() {
      assertTargetBRefused("VuE", B_UNPAIRED_X, "to(@@Src::x, Telescope.of(@@Tgt.class).field(@@Tgt::c))");
    }

    @Test
    @DisplayName("a target property beside a row reading through a source path")
    void targetBesideASourcePath() {
      assertTargetBRefused("VuF", B_UNPAIRED_X, "to(Telescope.of(@@Src.class).field(@@Src::x), @@Tgt::c)");
    }

    @Test
    @DisplayName("a target property beside a row given a path on both sides")
    void targetBesideTwoPaths() {
      assertTargetBRefused(
        "VuG",
        B_UNPAIRED_X,
        "to(Telescope.of(@@Src.class).field(@@Src::x), Telescope.of(@@Tgt.class).field(@@Tgt::c))"
      );
    }

    @Test
    @DisplayName("a target property beside a zip row")
    void targetBesideAZip() {
      assertTargetBRefused(
        "VuH",
        B_UNPAIRED_X,
        "zip(Telescope.of(@@Src.class).field(@@Src::x), Telescope.of(@@Tgt.class).field(@@Tgt::c))"
      );
    }

    @Test
    @DisplayName("a target property beside a constant row under when")
    void targetBesideAGatedConstant() {
      assertTargetBRefused("VuI", B_UNPAIRED, "when(s -> true, constant(@@Tgt::c, \"k\"))");
    }

    @Test
    @DisplayName("a target property beside a row writing through a path two properties deep")
    void targetBesideADeepTargetPath() {
      assertTargetBRefused(
        "VuJ",
        """
        record @@In(String v) {}
        record @@Src(String a, String x) {}
        record @@Tgt(String a, String b, @@In c) {}
        """,
        "to(@@Src::x, Telescope.of(@@Tgt.class).field(@@Tgt::c).field(@@In::v))"
      );
    }

    @Test
    @DisplayName("a source property beside a constant row")
    void sourceBesideAConstant() {
      assertSourceYRefused(
        "VuK",
        """
        record @@Src(String a, String y) {}
        record @@Tgt(String a, String c) {}
        """,
        "constant(@@Tgt::c, \"k\")"
      );
    }

    @Test
    @DisplayName("a source property beside a row writing through a target path")
    void sourceBesideATargetPath() {
      assertSourceYRefused("VuL", Y_UNPAIRED_X, "to(@@Src::x, Telescope.of(@@Tgt.class).field(@@Tgt::c))");
    }

    @Test
    @DisplayName("a source property beside a row reading through a source path")
    void sourceBesideASourcePath() {
      assertSourceYRefused("VuM", Y_UNPAIRED_X, "to(Telescope.of(@@Src.class).field(@@Src::x), @@Tgt::c)");
    }

    @Test
    @DisplayName("a source property beside a row reading through a source path under when")
    void sourceBesideAGatedSourcePath() {
      assertSourceYRefused(
        "VuN",
        Y_UNPAIRED_X,
        "when(s -> true, to(Telescope.of(@@Src.class).field(@@Src::x), @@Tgt::c))"
      );
    }

    @Test
    @DisplayName("a target property whose namesake a source path one field hop long reads, which no row writes")
    void namesakeOfAOneHopSourcePath() {
      final var outcome = verified(
        "VuV",
        """
          record @@Src(String a, String x) {}
          record @@Tgt(String a, String x, String c) {}
          """ +
          holder("Telescope.mapper(@@Src.class, @@Tgt.class, to(Telescope.of(@@Src.class).field(@@Src::x), @@Tgt::c))")
      );
      assertBothRefuse(outcome);
      assertEquals(
        PairingMessages.sameNameSourceRead("VuVSrc", "VuVTgt", "field", "field", "x"),
        outcome.construction()
      );
    }

    @Test
    @DisplayName("a target property of a pair of sealed cases, beside a constant row through the target interface")
    void targetOfASealedCase() {
      final var outcome = verified(
        "VuO",
        """
          sealed interface @@Sh permits @@Sq { String n(); }
          record @@Sq(String n) implements @@Sh {}
          sealed interface @@ShD permits @@SqD { String c(); }
          record @@SqD(String n, String b, String c) implements @@ShD {}
          """ +
          holder(
            "Telescope.mapper(@@Sh.class, @@ShD.class, io.github.eschizoid.telescope.mapping.Cases.cases(@@Sq.class," +
              " @@SqD.class), constant(@@ShD::c, \"k\"))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.noSameNameSource("VuOSq", "VuOSqD", "field", "field", "b"), outcome.construction());
    }

    @Test
    @DisplayName("not where constant and compute rows fill every target property nothing else pairs")
    void filledByConstantAndCompute() {
      assertBothAccept(
        verified(
          "VuP",
          B_UNPAIRED +
            holder(
              "Telescope.mapper(@@Src.class, @@Tgt.class, constant(@@Tgt::b, \"k\"), compute(@@Tgt::c, () -> \"k\"))"
            )
        )
      );
    }

    @Test
    @DisplayName("not where a path row writes the target property and reads the source property nothing else pairs")
    void pairedThroughATargetPath() {
      assertBothAccept(
        verified(
          "VuQ",
          X_INTO_C +
            holder(
              "Telescope.mapper(@@Src.class, @@Tgt.class, to(@@Src::x, Telescope.of(@@Tgt.class).field(@@Tgt::c)))"
            )
        )
      );
    }

    @Test
    @DisplayName("not where a path row reads the source property and writes the target property nothing else pairs")
    void pairedThroughASourcePath() {
      assertBothAccept(
        verified(
          "VuR",
          X_INTO_C +
            holder(
              "Telescope.mapper(@@Src.class, @@Tgt.class, to(Telescope.of(@@Src.class).field(@@Src::x), @@Tgt::c))"
            )
        )
      );
    }

    @Test
    @DisplayName("not where a target path steps again through the target's class, as construction counts it filling")
    void notThroughARecursiveTargetPath() {
      assertBothAccept(
        verified(
          "VuU",
          """
            record @@Src(String a, String x) {}
            record @@Tgt(String a, String b, @@Tgt c) {}
            """ +
            holder(
              "Telescope.mapper(@@Src.class, @@Tgt.class, to(@@Src::x," +
                " Telescope.of(@@Tgt.class).field(@@Tgt::c).field(@@Tgt::b)))"
            )
        )
      );
    }

    @Test
    @DisplayName("not on a mapperForward call, beside a constant row")
    void notForwardOnly() {
      assertBothAccept(
        verified(
          "VuS",
          """
            record @@Src(String a, String y) {}
            record @@Tgt(String a, String b, String c) {}
            """ +
            holder("Telescope.mapperForward(@@Src.class, @@Tgt.class, constant(@@Tgt::c, \"k\"))")
        )
      );
    }

    @Test
    @DisplayName("not on a nested pair no row is keyed to, beside a constant row on the mapper's pair")
    void notOnANestedPair() {
      assertBothAccept(
        verified(
          "VuT",
          """
            record @@Src(String a) {}
            record @@Tgt(String a, String b) {}
            record @@Outer(@@Src in) {}
            record @@OuterDto(@@Tgt in, String note) {}
            """ +
            holder("Telescope.mapper(@@Outer.class, @@OuterDto.class, constant(@@OuterDto::note, \"k\"))")
        )
      );
    }
  }
}
