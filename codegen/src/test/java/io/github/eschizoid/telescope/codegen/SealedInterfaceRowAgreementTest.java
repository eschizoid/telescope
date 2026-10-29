package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.compile;
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
 * A row whose accessors are declared on two sealed interfaces applies to every pair of their cases
 * the mapper converts, on a mapper between the two interfaces and below a field typed by them: each
 * case implements the accessor, so each has the property it names. A pair of cases that cannot take
 * the row, because a case has no such property or because the two properties' types do not pair, is
 * refused by name by construction and by the compile-time verifier alike.
 *
 * <p>Each case compiles its sources once with the verifier reporting warnings, then builds the
 * mapper those classes describe and converts a circle through it. Every case names its classes with
 * a prefix of its own, because one loader holds them all.
 */
class SealedInterfaceRowAgreementTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  /** Two sealed interfaces declaring the accessors a row names, and a frame holding each. */
  private static final String SHAPES = """
    package io.github.eschizoid.telescope.codegen;
    import static io.github.eschizoid.telescope.mapping.Cases.cases;
    import static io.github.eschizoid.telescope.mapping.Mapping.*;
    import io.github.eschizoid.telescope.Telescope;
    record @@Part(String v) {}
    record @@PartDto(String v) {}
    sealed interface @@Shape permits @@Circle, @@Square { String label(); @@Part part(); }
    record @@Circle(String name, String label, @@Part part) implements @@Shape {}
    record @@Square(String name, String label, @@Part part) implements @@Shape {}
    sealed interface @@ShapeDto permits @@CircleDto, @@SquareDto { String title(); @@PartDto part(); }
    record @@CircleDto(String name, String title, @@PartDto part) implements @@ShapeDto {}
    record @@SquareDto(String name, String title, @@PartDto part) implements @@ShapeDto {}
    record @@Frame(@@Shape shape) {}
    record @@FrameDto(@@ShapeDto shape) {}
    """;

  private static final String CASES =
    "cases(@@Circle.class, @@CircleDto.class), cases(@@Square.class, @@SquareDto.class)";

  private static final String CIRCLE = "new @@Circle(\"n\", \"l\", new @@Part(\"p\"))";

  private record Outcome(String result, String construction, List<String> warnings) {}

  /**
   * Compiles {@code code} with the verifier reporting warnings, defines what it compiled, and calls
   * the holder's {@code run()}: what it returned, or the message construction refused the mapper
   * with, and every warning the verifier reported.
   */
  private static Outcome outcome(final String prefix, final String code) {
    final var compilation = compileFully(
      List.of(new MapperVerifierProcessor()),
      List.of("-Atelescope.verify=warn"),
      source(PACKAGE + "." + prefix + "Holder", code.replace("@@", prefix))
    );
    assertTrue(compilation.success(), compilation::errorMessages);
    final var warnings = compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
      .map(d -> d.getMessage(Locale.ROOT))
      .toList();
    final var holder = compilation.define(MethodHandles.lookup()).get(PACKAGE + "." + prefix + "Holder");
    try {
      return new Outcome(String.valueOf(holder.getDeclaredMethod("run").invoke(null)), null, warnings);
    } catch (final InvocationTargetException e) {
      return new Outcome(null, e.getCause().getMessage(), warnings);
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A holder whose {@code run()} returns {@code call}. */
  private static String holder(final String call) {
    return "class @@Holder { static Object run() { return " + call + "; } }\n";
  }

  private static void assertBothApply(final Outcome outcome, final String expected) {
    assertNull(outcome.construction(), outcome::construction);
    assertEquals(List.of(), outcome.warnings());
    assertEquals(expected, outcome.result());
  }

  private static void assertBothRefuse(final Outcome outcome) {
    assertNotNull(outcome.construction(), "construction built the mapper");
    assertTrue(outcome.warnings().contains(outcome.construction()), () -> String.join("\n", outcome.warnings()));
  }

  @Nested
  @DisplayName("A row keyed to the two sealed interfaces applies to every pair of their cases")
  class Applied {

    @Test
    @DisplayName("to(src, tgt) on a mapper between the interfaces")
    void renameOnTheInterfaces() {
      assertBothApply(
        outcome(
          "SiA",
          SHAPES +
            holder(
              "Telescope.mapper(@@Shape.class, @@ShapeDto.class, " +
                CASES +
                ", to(@@Shape::label, @@ShapeDto::title)).forward(" +
                CIRCLE +
                ")"
            )
        ),
        "SiACircleDto[name=n, title=l, part=SiAPartDto[v=p]]"
      );
    }

    @Test
    @DisplayName("to(src, tgt) below a field typed by the interfaces")
    void renameBelowASealedField() {
      assertBothApply(
        outcome(
          "SiB",
          SHAPES +
            holder(
              "Telescope.mapper(@@Frame.class, @@FrameDto.class, " +
                CASES +
                ", to(@@Shape::label, @@ShapeDto::title)).forward(new @@Frame(" +
                CIRCLE +
                "))"
            )
        ),
        "SiBFrameDto[shape=SiBCircleDto[name=n, title=l, part=SiBPartDto[v=p]]]"
      );
    }

    @Test
    @DisplayName("via(src, tgt, mapper) below a field typed by the interfaces")
    void viaBelowASealedField() {
      assertBothApply(
        outcome(
          "SiC",
          SHAPES +
            holder(
              "Telescope.mapperForward(@@Frame.class, @@FrameDto.class, " +
                CASES +
                ", via(@@Shape::part, @@ShapeDto::part," +
                " Telescope.mapper(@@Part.class, @@PartDto.class, to(@@Part::v, @@PartDto::v, v -> v + \"!\", v -> v))))" +
                ".forward(new @@Frame(" +
                CIRCLE +
                "))"
            )
        ),
        "SiCFrameDto[shape=SiCCircleDto[name=n, title=null, part=SiCPartDto[v=p!]]]"
      );
    }

    @Test
    @DisplayName("drop(src, target) beside a rename, on a mapper that converts both ways")
    void dropOnTheInterfaces() {
      assertBothApply(
        outcome(
          "SiD",
          """
            package io.github.eschizoid.telescope.codegen;
            import static io.github.eschizoid.telescope.mapping.Cases.cases;
            import static io.github.eschizoid.telescope.mapping.Mapping.*;
            import io.github.eschizoid.telescope.Telescope;
            sealed interface @@Shape permits @@Circle, @@Square { String label(); String note(); }
            record @@Circle(String label, String note) implements @@Shape {}
            record @@Square(String label, String note) implements @@Shape {}
            sealed interface @@ShapeDto permits @@CircleDto, @@SquareDto { String title(); }
            record @@CircleDto(String title) implements @@ShapeDto {}
            record @@SquareDto(String title) implements @@ShapeDto {}
            """ +
            holder(
              "Telescope.mapper(@@Shape.class, @@ShapeDto.class, " +
                CASES +
                ", to(@@Shape::label, @@ShapeDto::title), drop(@@Shape::note, @@ShapeDto.class))" +
                ".forward(new @@Circle(\"l\", \"x\"))"
            )
        ),
        "SiDCircleDto[title=l]"
      );
    }

    @Test
    @DisplayName("constant(tgt, value) on a mapper between the interfaces")
    void constantOnTheInterfaces() {
      assertBothApply(
        outcome(
          "SiE",
          SHAPES +
            holder(
              "Telescope.mapperForward(@@Shape.class, @@ShapeDto.class, " +
                CASES +
                ", constant(@@ShapeDto::title, \"K\")).forward(" +
                CIRCLE +
                ")"
            )
        ),
        "SiECircleDto[name=n, title=K, part=SiEPartDto[v=p]]"
      );
    }
  }

  @Nested
  @DisplayName("A row keyed to the two sealed interfaces, beside rows of a pair of their cases")
  class BesideOwnRows {

    @Test
    @DisplayName("gives way to the pair's own same-typed row converting the same target property")
    void ownPassThroughRowWins() {
      assertBothApply(
        outcome(
          "SiI",
          SHAPES +
            holder(
              "Telescope.mapper(@@Frame.class, @@FrameDto.class, " +
                CASES +
                ", to(@@Circle::name, @@CircleDto::title), to(@@Circle::name, @@CircleDto::name)," +
                " to(@@Shape::label, @@ShapeDto::title)).forward(new @@Frame(" +
                CIRCLE +
                "))"
            )
        ),
        "SiIFrameDto[shape=SiICircleDto[name=n, title=n, part=SiIPartDto[v=p]]]"
      );
    }

    @Test
    @DisplayName("gives way to the pair's own row converting the same target property its own way")
    void ownConvertingRowWins() {
      assertBothApply(
        outcome(
          "SiJ",
          SHAPES +
            holder(
              "Telescope.mapper(@@Frame.class, @@FrameDto.class, " +
                CASES +
                ", to(@@Circle::name, @@CircleDto::title, s -> s + \"!\", s -> s), to(@@Circle::name, @@CircleDto::name)," +
                " to(@@Shape::label, @@ShapeDto::title))" +
                ".forward(new @@Frame(" +
                CIRCLE +
                "))"
            )
        ),
        "SiJFrameDto[shape=SiJCircleDto[name=n, title=n!, part=SiJPartDto[v=p]]]"
      );
    }
  }

  @Nested
  @DisplayName("Two rows keyed to two pairs of sealed interfaces claiming one property of a pair of cases")
  class TwoInterfaceRows {

    /** A sealed hierarchy nested in another, each side, and a holder of the outer interface. */
    private static final String ANIMALS = """
      package io.github.eschizoid.telescope.codegen;
      import static io.github.eschizoid.telescope.mapping.Cases.cases;
      import static io.github.eschizoid.telescope.mapping.Mapping.*;
      import io.github.eschizoid.telescope.Telescope;
      sealed interface @@Animal permits @@Pet, @@Wild { String name(); }
      sealed interface @@Pet extends @@Animal permits @@Cat { String nick(); }
      record @@Cat(String name, String nick) implements @@Pet {}
      record @@Wild(String name) implements @@Animal {}
      sealed interface @@AnimalDto permits @@PetDto, @@WildDto { String title(); }
      sealed interface @@PetDto extends @@AnimalDto permits @@CatDto { String alias(); }
      record @@CatDto(String title, String alias) implements @@PetDto {}
      record @@WildDto(String title) implements @@AnimalDto {}
      record @@Zoo(@@Animal a) {}
      record @@ZooDto(@@AnimalDto a) {}
      """;

    private static final String ANIMAL_CASES =
      "cases(@@Pet.class, @@PetDto.class), cases(@@Wild.class, @@WildDto.class), cases(@@Cat.class, @@CatDto.class)";

    @Test
    @DisplayName("apply the one keyed to the interfaces extending the other's")
    void moreSpecificInterfacesWin() {
      assertBothApply(
        outcome(
          "SiO",
          ANIMALS +
            holder(
              "Telescope.mapperForward(@@Zoo.class, @@ZooDto.class, " +
                ANIMAL_CASES +
                ", to(@@Animal::name, @@AnimalDto::title), to(@@Pet::nick, @@PetDto::title))" +
                ".forward(new @@Zoo(new @@Cat(\"n\", \"k\")))"
            )
        ),
        "SiOZooDto[a=SiOCatDto[title=k, alias=null]]"
      );
    }

    @Test
    @DisplayName("apply the one keyed to the interfaces extending the other's, whichever is given first")
    void moreSpecificInterfacesWinGivenFirst() {
      assertBothApply(
        outcome(
          "SiP",
          ANIMALS +
            holder(
              "Telescope.mapperForward(@@Zoo.class, @@ZooDto.class, " +
                ANIMAL_CASES +
                ", to(@@Pet::nick, @@PetDto::title), to(@@Animal::name, @@AnimalDto::title))" +
                ".forward(new @@Zoo(new @@Wild(\"w\")))"
            )
        ),
        "SiPZooDto[a=SiPWildDto[title=w]]"
      );
    }

    @Test
    @DisplayName("are refused by name on both paths where neither pair of interfaces extends the other")
    void unrelatedInterfacesAreRefused() {
      final var outcome = outcome(
        "SiQ",
        """
          package io.github.eschizoid.telescope.codegen;
          import static io.github.eschizoid.telescope.mapping.Cases.cases;
          import static io.github.eschizoid.telescope.mapping.Mapping.*;
          import io.github.eschizoid.telescope.Telescope;
          sealed interface @@Named permits @@Duo { String name(); }
          sealed interface @@Tagged permits @@Duo { String tag(); }
          record @@Duo(String name, String tag) implements @@Named, @@Tagged {}
          sealed interface @@NamedDto permits @@DuoDto { String title(); }
          sealed interface @@TaggedDto permits @@DuoDto { String title(); }
          record @@DuoDto(String title) implements @@NamedDto, @@TaggedDto {}
          record @@Both(@@Named n, @@Tagged t) {}
          record @@BothDto(@@NamedDto n, @@TaggedDto t) {}
          """ +
          holder(
            "Telescope.mapperForward(@@Both.class, @@BothDto.class, cases(@@Duo.class, @@DuoDto.class)," +
              " to(@@Named::name, @@NamedDto::title), to(@@Tagged::tag, @@TaggedDto::title))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(
        PairingMessages.interfaceRowsClash(
          "name -> title",
          PACKAGE + ".SiQNamed",
          PACKAGE + ".SiQNamedDto",
          "tag -> title",
          PACKAGE + ".SiQTagged",
          PACKAGE + ".SiQTaggedDto",
          PACKAGE + ".SiQDuo",
          PACKAGE + ".SiQDuoDto",
          "title"
        ),
        outcome.construction()
      );
    }
  }

  @Nested
  @DisplayName("A row outranked on a pair claims nothing there, and two rows claiming one property are refused")
  class ClaimsOnAPair {

    /**
     * One sealed hierarchy nested in another, each side, with a case holding a case of the outer.
     */
    private static final String NESTED = """
      package io.github.eschizoid.telescope.codegen;
      import static io.github.eschizoid.telescope.mapping.Mapping.*;
      import io.github.eschizoid.telescope.Telescope;
      sealed interface @@A permits @@P, @@Wild { String name(); }
      sealed interface @@P extends @@A permits @@Cat { String nick(); }
      record @@Wild(String name) implements @@A {}
      record @@Cat(String name, String nick, @@Wild w) implements @@P {}
      sealed interface @@AD permits @@PD, @@WildD { String title(); }
      sealed interface @@PD extends @@AD permits @@CatD {}
      record @@WildD(String title) implements @@AD {}
      record @@CatD(String title, @@WildD w) implements @@PD {}
      """;

    @Test
    @DisplayName("a source property only an outranked row reads is unmatched on the call's own pair")
    void outrankedRowClaimsNothingOnTheCallsPair() {
      final var outcome = outcome(
        "SiS",
        NESTED +
          holder("Telescope.mapper(@@Cat.class, @@CatD.class, to(@@A::name, @@AD::title), to(@@P::nick, @@PD::title))")
      );
      assertBothRefuse(outcome);
      assertEquals(
        PairingMessages.noSameNameTarget("SiSCat", "SiSCatD", "field", "field", "name"),
        outcome.construction()
      );
    }

    @Test
    @DisplayName("the unmatched source property is named before the outranked row the mapper never visits")
    void unmatchedNamedFirst() {
      final var outcome = outcome(
        "SiT",
        """
          package io.github.eschizoid.telescope.codegen;
          import static io.github.eschizoid.telescope.mapping.Mapping.*;
          import io.github.eschizoid.telescope.Telescope;
          sealed interface @@A permits @@P { String name(); }
          sealed interface @@P extends @@A permits @@Cat { String nick(); }
          record @@Cat(String name, String nick, String x) implements @@P {}
          sealed interface @@AD permits @@PD { String title(); }
          sealed interface @@PD extends @@AD permits @@CatD {}
          record @@CatD(String title, String alias) implements @@PD {}
          """ +
          holder(
            "Telescope.mapper(@@Cat.class, @@CatD.class, to(@@A::name, @@AD::title), to(@@P::nick, @@PD::title)," +
              " to(@@Cat::x, @@CatD::alias))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(outcome.construction(), outcome.warnings().getFirst());
    }

    @Test
    @DisplayName("two rows keyed to one pair of sealed interfaces writing one target property")
    void twoRowsOnOnePairOfInterfaces() {
      final var outcome = outcome(
        "SiU",
        NESTED +
          holder(
            "Telescope.mapperForward(@@Cat.class, @@CatD.class, to(@@P::name, @@PD::title), to(@@P::nick, @@PD::title))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.duplicateTargetRow("SiUCat", "SiUCatD", "title"), outcome.construction());
    }

    @Test
    @DisplayName("two rows keyed to one nested pair writing one target property")
    void twoRowsOnOneNestedPair() {
      final var outcome = outcome(
        "SiV",
        """
          package io.github.eschizoid.telescope.codegen;
          import static io.github.eschizoid.telescope.mapping.Mapping.*;
          import io.github.eschizoid.telescope.Telescope;
          record @@Inner(String a, String b) {}
          record @@InnerD(String t) {}
          record @@Outer(@@Inner in) {}
          record @@OuterD(@@InnerD in) {}
          """ +
          holder(
            "Telescope.mapperForward(@@Outer.class, @@OuterD.class, to(@@Inner::a, @@InnerD::t), to(@@Inner::b, @@InnerD::t))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(PairingMessages.duplicateTargetRow("SiVInner", "SiVInnerD", "t"), outcome.construction());
    }
  }

  @Test
  @DisplayName("a call verified after another keeps none of the other's claims")
  void noClaimsCarriedBetweenCalls() {
    final var outcome = outcome(
      "SiR",
      """
        package io.github.eschizoid.telescope.codegen;
        import static io.github.eschizoid.telescope.mapping.Cases.cases;
        import static io.github.eschizoid.telescope.mapping.Mapping.*;
        import io.github.eschizoid.telescope.Telescope;
        record @@In(Integer v) {}
        record @@InDto(String v) {}
        record @@Top(@@In i) {}
        record @@TopDto(@@InDto i) {}
        sealed interface @@K2 permits @@KB { @@In inner(); }
        record @@KB(@@In inner) implements @@K2 {}
        sealed interface @@K2Dto permits @@KBDto { @@InDto inner(); }
        record @@KBDto(@@InDto inner) implements @@K2Dto {}
        """ +
        holder(
          "false ? (Object) Telescope.mapper(@@Top.class, @@TopDto.class, to(@@In::v, @@InDto::v, i -> String.valueOf(i)," +
            " x -> Integer.valueOf(x))) : Telescope.mapper(@@K2.class, @@K2Dto.class, cases(@@KB.class, @@KBDto.class)," +
            " to(@@K2::inner, @@K2Dto::inner))"
        )
    );
    assertBothRefuse(outcome);
    assertTrue(outcome.construction().contains("'v'"), outcome.construction());
  }

  @Nested
  @DisplayName("A pair of cases the mapper meets as itself takes the row, whether or not it meets the interfaces")
  class MetAsItself {

    /** Interfaces whose cases the row's conversion would refuse by name. */
    private static final String CODES = """
      package io.github.eschizoid.telescope.codegen;
      import static io.github.eschizoid.telescope.mapping.Cases.cases;
      import static io.github.eschizoid.telescope.mapping.Mapping.*;
      import io.github.eschizoid.telescope.Telescope;
      sealed interface @@K permits @@KA { Integer code(); }
      record @@KA(Integer code) implements @@K {}
      sealed interface @@KDto permits @@KADto { String code(); }
      record @@KADto(String code) implements @@KDto {}
      record @@KMix(@@K k, @@KA ka) {}
      record @@KMixDto(@@KDto k, @@KADto ka) {}
      sealed interface @@J permits @@JA { String label(); }
      record @@JA(Integer code, String label) implements @@J {}
      sealed interface @@JDto permits @@JADto { String code(); }
      record @@JADto(String code) implements @@JDto {}
      record @@JMix(@@J j, @@JA ja) {}
      record @@JMixDto(@@JDto j, @@JADto ja) {}
      """;

    @Test
    @DisplayName("a row converting a property whose same-named counterpart is of another type")
    void convertingRowOnACaseHeldAsItself() {
      assertBothApply(
        outcome(
          "SiK",
          CODES +
            holder(
              "Telescope.mapper(@@KMix.class, @@KMixDto.class, cases(@@KA.class, @@KADto.class)," +
                " to(@@K::code, @@KDto::code, i -> String.valueOf(i), s -> Integer.valueOf(s)))" +
                ".forward(new @@KMix(new @@KA(1), new @@KA(2)))"
            )
        ),
        "SiKKMixDto[k=SiKKADto[code=1], ka=SiKKADto[code=2]]"
      );
    }

    @Test
    @DisplayName("a row renaming onto a property whose same-named counterpart is of another type")
    void renamingRowOnACaseHeldAsItself() {
      assertBothApply(
        outcome(
          "SiL",
          CODES +
            holder(
              "Telescope.mapperForward(@@JMix.class, @@JMixDto.class, cases(@@JA.class, @@JADto.class)," +
                " to(@@J::label, @@JDto::code)).forward(new @@JMix(new @@JA(1, \"x\"), new @@JA(2, \"y\")))"
            )
        ),
        "SiLJMixDto[j=SiLJADto[code=x], ja=SiLJADto[code=y]]"
      );
    }

    @Test
    @DisplayName("a mapper holding only a case, and never the interfaces")
    void onlyACase() {
      assertBothApply(
        outcome(
          "SiM",
          SHAPES +
            "record @@Only(@@Circle c) {}\nrecord @@OnlyDto(@@CircleDto c) {}\n" +
            holder(
              "Telescope.mapper(@@Only.class, @@OnlyDto.class, to(@@Shape::label, @@ShapeDto::title)).forward(new @@Only(" +
                CIRCLE +
                "))"
            )
        ),
        "SiMOnlyDto[c=SiMCircleDto[name=n, title=l, part=SiMPartDto[v=p]]]"
      );
    }

    @Test
    @DisplayName("a mapper between two cases")
    void betweenTwoCases() {
      assertBothApply(
        outcome(
          "SiN",
          SHAPES +
            holder(
              "Telescope.mapper(@@Circle.class, @@CircleDto.class, to(@@Shape::label, @@ShapeDto::title)).forward(" +
                CIRCLE +
                ")"
            )
        ),
        "SiNCircleDto[name=n, title=l, part=SiNPartDto[v=p]]"
      );
    }

    @Test
    @DisplayName("a row reading a property whose same-named target counterpart is of another type")
    void readingRowOnACaseHeldAsItself() {
      assertBothApply(
        outcome(
          "SiW",
          """
          package io.github.eschizoid.telescope.codegen;
          import static io.github.eschizoid.telescope.mapping.Mapping.*;
          import io.github.eschizoid.telescope.Telescope;
          sealed interface @@N permits @@NA { String name(); }
          record @@NA(String name) implements @@N {}
          sealed interface @@NDto permits @@NADto { String title(); }
          record @@NADto(Long name, String title) implements @@NDto {}
          record @@NOnly(@@NA a) {}
          record @@NOnlyDto(@@NADto a) {}
          """ +
            holder(
              "Telescope.mapperForward(@@NOnly.class, @@NOnlyDto.class, to(@@N::name, @@NDto::title))" +
                ".forward(new @@NOnly(new @@NA(\"x\")))"
            )
        ),
        "SiWNOnlyDto[a=SiWNADto[name=null, title=x]]"
      );
    }
  }

  @Nested
  @DisplayName("A pair of cases that cannot take the row is refused by name on both paths")
  class Refused {

    /** A target case that implements the accessor with a method that is no record component. */
    private static final String UNPROPERTIED = """
      package io.github.eschizoid.telescope.codegen;
      import static io.github.eschizoid.telescope.mapping.Cases.cases;
      import static io.github.eschizoid.telescope.mapping.Mapping.*;
      import io.github.eschizoid.telescope.Telescope;
      sealed interface @@Shape permits @@Circle, @@Square { String label(); }
      record @@Circle(String label) implements @@Shape {}
      record @@Square(String label) implements @@Shape {}
      sealed interface @@ShapeDto permits @@CircleDto, @@SquareDto { String title(); }
      record @@CircleDto(String title) implements @@ShapeDto {}
      record @@SquareDto(String side) implements @@ShapeDto { public String title() { return side; } }
      record @@Frame(@@Shape shape) {}
      record @@FrameDto(@@ShapeDto shape) {}
      """;

    @Test
    @DisplayName("a case whose accessor reads no property, below a field typed by the interfaces")
    void caseWithNoSuchProperty() {
      final var outcome = outcome(
        "SiF",
        UNPROPERTIED +
          holder(
            "Telescope.mapperForward(@@Frame.class, @@FrameDto.class, " +
              CASES +
              ", to(@@Shape::label, @@ShapeDto::title))"
          )
      );
      assertBothRefuse(outcome);
      assertEquals(
        PairingMessages.interfaceRowOffCase(
          "label -> title",
          PACKAGE + ".SiFShape",
          PACKAGE + ".SiFShapeDto",
          PACKAGE + ".SiFSquare",
          PACKAGE + ".SiFSquareDto",
          PACKAGE + ".SiFSquareDto",
          "title"
        ),
        outcome.construction()
      );
    }

    @Test
    @DisplayName("a constant on a case whose accessor reads no property")
    void constantOnACaseWithNoSuchProperty() {
      final var outcome = outcome(
        "SiG",
        UNPROPERTIED +
          holder(
            "Telescope.mapperForward(@@Shape.class, @@ShapeDto.class, " +
              CASES +
              ", constant(@@ShapeDto::title, \"K\"))"
          )
      );
      assertBothRefuse(outcome);
      assertTrue(outcome.construction().contains("SiGSquareDto has no property 'title'"), outcome.construction());
    }

    @Test
    @DisplayName("two cases whose properties the row pairs have types that do not pair")
    void casePropertiesThatDoNotPair() {
      final var outcome = outcome(
        "SiH",
        """
          package io.github.eschizoid.telescope.codegen;
          import static io.github.eschizoid.telescope.mapping.Cases.cases;
          import static io.github.eschizoid.telescope.mapping.Mapping.*;
          import io.github.eschizoid.telescope.Telescope;
          import java.util.List;
          sealed interface @@Shape permits @@Circle, @@Square { Object size(); }
          record @@Circle(Integer size) implements @@Shape {}
          record @@Square(Integer size) implements @@Shape {}
          sealed interface @@ShapeDto permits @@CircleDto, @@SquareDto { Object size(); }
          record @@CircleDto(List<String> size) implements @@ShapeDto {}
          record @@SquareDto(Integer size) implements @@ShapeDto {}
          """ +
          holder(
            "Telescope.mapperForward(@@Shape.class, @@ShapeDto.class, " +
              CASES +
              ", to(@@Shape::size, @@ShapeDto::size))"
          )
      );
      assertBothRefuse(outcome);
      assertTrue(outcome.construction().contains("'size → size'"), outcome.construction());
    }
  }

  @Test
  @DisplayName("a @Bridge on a sealed interface that sets how fields convert is refused by name")
  void sealedBridgeWithFieldSettings() {
    final var compilation = compile(
      List.of(new BridgeProcessor()),
      source(
        "demo.Shape",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.*;
        @Bridge(value = ShapeDto.class, renames = @Rename(source = "label", target = "title"), drops = "note")
        public sealed interface Shape permits Circle { String label(); }
        """
      ),
      source(
        "demo.Circle",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.*;
        @Bridge(CircleDto.class)
        public record Circle(String label, String title) implements Shape {}
        """
      ),
      source("demo.ShapeDto", "package demo; public sealed interface ShapeDto permits CircleDto { String title(); }"),
      source(
        "demo.CircleDto",
        "package demo; public record CircleDto(String label, String title) implements ShapeDto {}"
      )
    );
    assertFalse(compilation.success());
    assertTrue(
      compilation.hasError(
        "@Bridge sealed Shape converts each case through the case's own @Bridge, which this bridge's drops, renames" +
          " would not reach. Declare them on the @Bridge of each case they apply to."
      ),
      compilation::errorMessages
    );
  }
}
