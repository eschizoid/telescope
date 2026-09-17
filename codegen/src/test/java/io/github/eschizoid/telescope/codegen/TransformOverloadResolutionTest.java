package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The fit check decides what a transform's two directions carry by resolving which overload the
 * emitted call site will bind to. Where its answer differs from the one Java gives, the row is
 * judged against a method that is not called — and the check accepts it, so the disagreement lands
 * as a javac error inside a file the author never wrote.
 *
 * <p>The cases below are a verdict matrix rather than separate tests: overload shape against field
 * types against the expected verdict. Resolution rules interact, so a change that fixes one row
 * commonly moves another, and a matrix shows that in one run.
 *
 * <p>They compile through the full pipeline. A wrong verdict is visible only once the generated
 * file is attributed, which the processing-only harness never does.
 */
class TransformOverloadResolutionTest {

  /**
   * @param accept whether the pair should compile, which for a rejection means the check refused it
   * @param expected a fragment of the diagnostic, so a row cannot pass by being refused for an
   *     unrelated reason
   */
  private record Case(
    String label,
    String fnBody,
    String srcField,
    String tgtField,
    String extra,
    boolean accept,
    String expected
  ) {
    @Override
    public String toString() {
      return label;
    }
  }

  /** Two overloads Java can order: String is more specific than CharSequence, so String wins. */
  private static final String ORDERABLE = """
    public final class Fn implements BridgeFn<CharSequence, Integer> {
      @Override public Integer forward(final CharSequence c) { return -1; }
      public Integer forward(final String s) { return s.length(); }
      @Override public CharSequence backward(final Integer i) { return String.valueOf(i); }
    }
    """;

  /** Neither parameter is more specific, and a String is both — so Java refuses to choose. */
  private static final String AMBIGUOUS = """
    public final class Fn implements BridgeFn<CharSequence, Integer> {
      @Override public Integer forward(final CharSequence c) { return c.length(); }
      public Integer forward(final Comparable<?> c) { return 0; }
      @Override public CharSequence backward(final Integer i) { return String.valueOf(i); }
    }
    """;

  /** The same two, declared the other way round. */
  private static final String AMBIGUOUS_SWAPPED = """
    public final class Fn implements BridgeFn<CharSequence, Integer> {
      public Integer forward(final Comparable<?> c) { return 0; }
      @Override public Integer forward(final CharSequence c) { return c.length(); }
      @Override public CharSequence backward(final Integer i) { return String.valueOf(i); }
    }
    """;

  /** An int binds the widening long, not the boxed Integer — and here that one does not fit. */
  private static final String WIDENING_MISFIT = """
    public final class Fn implements BridgeFn<Integer, String> {
      @Override public String forward(final Integer v) { return String.valueOf(v); }
      public Object forward(final long v) { return "long:" + v; }
      @Override public Integer backward(final String s) { return Integer.valueOf(s); }
    }
    """;

  /**
   * The same shape with the verdicts the other way round: the widening one is the one that fits.
   */
  private static final String WIDENING_FITS = """
    public final class Fn implements BridgeFn<Integer, Object> {
      @Override public Object forward(final Integer v) { return v; }
      public String forward(final long v) { return "long:" + v; }
      @Override public Integer backward(final Object o) { return 0; }
    }
    """;

  private static List<Case> cases() {
    return List.of(
      new Case(
        "an overload pair Java can order binds the more specific one",
        ORDERABLE,
        "String",
        "Integer",
        ", forwardOnly = true",
        true,
        null
      ),
      new Case(
        "two equally applicable overloads are refused, not resolved by declaration order",
        AMBIGUOUS,
        "String",
        "Integer",
        ", forwardOnly = true",
        false,
        "is ambiguous"
      ),
      new Case(
        "the same pair declared the other way round gives the same answer",
        AMBIGUOUS_SWAPPED,
        "String",
        "Integer",
        ", forwardOnly = true",
        false,
        "is ambiguous"
      ),
      new Case(
        "an int argument binds the widening overload, and its return is what gets checked",
        WIDENING_MISFIT,
        "int",
        "String",
        ", forwardOnly = true",
        false,
        "does not fit forward"
      ),
      new Case(
        "the same shape is accepted when the widening overload is the one that fits",
        WIDENING_FITS,
        "int",
        "String",
        ", forwardOnly = true",
        true,
        null
      )
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @DisplayName("the check binds the overload Java binds, in every shape where the two could differ")
  void verdictMatrix(final Case c) {
    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      new JavaFileObject[] {
        ProcessorHarness.source(
          "demo.Fn",
          "package demo;\nimport io.github.eschizoid.telescope.conversion.BridgeFn;\n" + c.fnBody()
        ),
        ProcessorHarness.source(
          "demo.Src",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Transform;
          @Bridge(value = demo.Tgt.class, transforms = {
            @Transform(field = "v", using = demo.Fn.class%s)
          })
          public record Src(%s v) {}
          """.formatted(c.extra(), c.srcField())
        ),
        ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(%s v) {}".formatted(c.tgtField())),
      }
    );

    if (c.accept()) {
      assertTrue(compilation.success(), () -> "should have been accepted: " + compilation.errorMessages());
      return;
    }
    assertFalse(compilation.success(), "should have been refused");
    assertTrue(
      compilation.hasError(c.expected()),
      () -> "refused for the wrong reason, wanted \"" + c.expected() + "\": " + compilation.errorMessages()
    );
    // A refusal the check did not make is the failure this exists to prevent: the row was accepted,
    // the bridge was emitted, and javac rejected the file the author never wrote.
    assertFalse(
      compilation.errorMessages().contains("reference to forward is ambiguous"),
      () -> "the raw javac error is what a diagnostic replaces: " + compilation.errorMessages()
    );
    assertFalse(
      compilation.errorMessages().contains("cannot be converted to"),
      () -> "the raw javac error is what a diagnostic replaces: " + compilation.errorMessages()
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @DisplayName("every rejection names the field, so the author knows which row to change")
  void everyRejectionNamesTheField(final Case c) {
    if (c.accept()) return;

    final var compilation = ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      new JavaFileObject[] {
        ProcessorHarness.source(
          "demo.Fn",
          "package demo;\nimport io.github.eschizoid.telescope.conversion.BridgeFn;\n" + c.fnBody()
        ),
        ProcessorHarness.source(
          "demo.Src",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Transform;
          @Bridge(value = demo.Tgt.class, transforms = {
            @Transform(field = "v", using = demo.Fn.class%s)
          })
          public record Src(%s v) {}
          """.formatted(c.extra(), c.srcField())
        ),
        ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(%s v) {}".formatted(c.tgtField())),
      }
    );

    assertEquals(false, compilation.success());
    assertTrue(
      compilation.hasError("field=\"v\""),
      () -> "the diagnostic should name the row: " + compilation.errorMessages()
    );
  }
}
