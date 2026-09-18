package io.github.eschizoid.telescope.codegen;

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
 * types against direction against the expected verdict. Resolution rules interact, so a change that
 * fixes one row commonly moves another, and a matrix shows that in one run.
 *
 * <p>They compile through the full pipeline. A wrong verdict is visible only once the generated
 * file is attributed, which the processing-only harness never does.
 */
class TransformOverloadResolutionTest {

  /**
   * @param extra extra {@code @Transform} arguments, which decides whether a backward is emitted at
   *     all and therefore whether its overloads are a question anything asks
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

  /** Forward resolves cleanly; it is backward whose overloads cannot be ordered. */
  private static final String AMBIGUOUS_BACKWARD = """
    public final class Fn implements BridgeFn<Integer, Object> {
      @Override public String forward(final Integer i) { return String.valueOf(i); }
      @Override public Integer backward(final Object o) { return 0; }
      public Integer backward(final CharSequence c) { return 1; }
      public Integer backward(final Comparable<?> c) { return 2; }
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

  /**
   * char reaches int by widening and Character by boxing — another primitive path to the same rule.
   */
  private static final String CHAR_WIDENING = """
    public final class Fn implements BridgeFn<Character, Object> {
      @Override public Object forward(final Character c) { return c; }
      public String forward(final int i) { return "widened:" + i; }
      @Override public Character backward(final Object o) { return 'x'; }
    }
    """;

  /**
   * Raw, so there are no type arguments to stand in for a direction the resolver cannot bind. Its
   * forward does not fit and its backward is ambiguous — two independent problems in one class.
   *
   * <p>It implements the raw interface's own two methods rather than overloads of them. A class
   * that does not is rejected by javac on its own, and a processor error stops compilation before
   * the class body is attributed — so the row would pass on a diagnostic about the transform while
   * the fixture was a class nobody could write.
   */
  private static final String RAW_AMBIGUOUS_BACKWARD = """
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public final class Fn implements BridgeFn {
      public Object forward(final Object o) { return o; }
      public Object backward(final Object o) { return 0; }
      public Integer backward(final CharSequence c) { return 1; }
      public Integer backward(final Comparable<?> c) { return 2; }
    }
    """;

  private static final String FORWARD_ONLY = ", forwardOnly = true";

  private static List<Case> cases() {
    return List.of(
      new Case(
        "an overload pair Java can order binds the more specific one",
        ORDERABLE,
        "String",
        "Integer",
        FORWARD_ONLY,
        true,
        null
      ),
      new Case(
        "two equally applicable overloads are refused, not resolved by declaration order",
        AMBIGUOUS,
        "String",
        "Integer",
        FORWARD_ONLY,
        false,
        "forward is ambiguous"
      ),
      new Case(
        "the same pair declared the other way round gives the same answer",
        AMBIGUOUS_SWAPPED,
        "String",
        "Integer",
        FORWARD_ONLY,
        false,
        "forward is ambiguous"
      ),
      new Case(
        "an int argument binds the widening overload, and its return is what gets checked",
        WIDENING_MISFIT,
        "int",
        "String",
        FORWARD_ONLY,
        false,
        "does not fit forward"
      ),
      new Case(
        "the same shape is accepted when the widening overload is the one that fits",
        WIDENING_FITS,
        "int",
        "String",
        FORWARD_ONLY,
        true,
        null
      ),
      new Case(
        "a char argument binds the widening int overload, not the boxed Character one",
        CHAR_WIDENING,
        "char",
        "String",
        FORWARD_ONLY,
        true,
        null
      ),
      // The pair that matters most, because the two rows differ only in whether the direction
      // they
      // are about is emitted at all.
      new Case(
        "an ambiguous backward is refused when a backward is emitted",
        AMBIGUOUS_BACKWARD,
        "Integer",
        "String",
        "",
        false,
        "backward is ambiguous"
      ),
      new Case(
        "an unusable backward does not take the forward check down with it",
        RAW_AMBIGUOUS_BACKWARD,
        "Integer",
        "String",
        FORWARD_ONLY,
        false,
        "does not fit forward"
      ),
      new Case(
        "and accepted when forwardOnly means there is no backward to be ambiguous",
        AMBIGUOUS_BACKWARD,
        "Integer",
        "String",
        FORWARD_ONLY,
        true,
        null
      )
    );
  }

  /** Only the rows that should be refused, so no row in the second test asserts nothing. */
  private static List<Case> rejectedCases() {
    return cases()
      .stream()
      .filter(c -> !c.accept())
      .toList();
  }

  private static ProcessorHarness.Compilation compile(final Case c) {
    return ProcessorHarness.compileFully(
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
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @DisplayName("the check binds the overload Java binds, in every shape where the two could differ")
  void verdictMatrix(final Case c) {
    final var compilation = compile(c);

    if (c.accept()) {
      assertTrue(compilation.success(), () -> "should have been accepted: " + compilation.errorMessages());
      return;
    }
    // Both halves are needed, and the first is the one that does the work. javac's own ambiguity
    // error says "reference to backward is ambiguous", which contains the fragment a row looking
    // for "is ambiguous" would accept -- so a row could pass by being refused inside the generated
    // file, which is the outcome this check exists to replace. Every diagnostic from the check
    // names the row it is about and none of javac's do, so requiring that is what separates them,
    // and it does not depend on javac's wording the way matching its text would.
    assertTrue(
      compilation.hasError("@Transform field=\"v\""),
      () -> "this must be the check's own refusal, not javac's inside a generated file: " + compilation.errorMessages()
    );
    assertTrue(
      compilation.hasError(c.expected()),
      () -> "refused for the wrong reason, wanted \"" + c.expected() + "\": " + compilation.errorMessages()
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @DisplayName("every transform in the matrix is a class somebody could actually write")
  void everyFixtureCompilesOnItsOwn(final Case c) {
    // A processor error stops compilation before class bodies are attributed, so a fixture that
    // javac would reject on its own still produces exactly the diagnostic a row asserts — and the
    // row passes while describing a shape no user could reach. One of these was an overload of the
    // raw interface's method rather than an implementation of it, and nothing here noticed.
    final var compilation = ProcessorHarness.compileFully(
      List.of(),
      List.of(),
      new JavaFileObject[] {
        ProcessorHarness.source(
          "demo.Fn",
          "package demo;\nimport io.github.eschizoid.telescope.conversion.BridgeFn;\n" + c.fnBody()
        ),
      }
    );

    assertTrue(compilation.success(), () -> "the fixture itself does not compile: " + compilation.errorMessages());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejectedCases")
  @DisplayName("every rejection names the field, so the author knows which row to change")
  void everyRejectionNamesTheField(final Case c) {
    final var compilation = compile(c);

    assertFalse(compilation.success(), "this row should have been refused");
    assertTrue(
      compilation.hasError("field=\"v\""),
      () -> "the diagnostic should name the row: " + compilation.errorMessages()
    );
  }
}
