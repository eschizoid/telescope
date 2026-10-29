package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A {@code BridgeFn<A, B>} is used in both directions unless the row says otherwise. Forward reads
 * the source field, hands it to {@code A}, and stores {@code B} into the target field; backward
 * makes the same journey in reverse and needs the other two conversions. A pair can satisfy one and
 * not the other, so checking only forward accepts rows whose emitted backward cannot compile.
 *
 * <p>These compile through the full pipeline, because a mismatch that reaches emission surfaces as
 * a javac error inside the generated file — which the processing-only harness never attributes.
 */
class TransformDirectionFitTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
  }

  /**
   * {@code demo.Src}, declared by {@code declaration} with {@code body}, whose {@code @Bridge}
   * field converts into {@code demo.Tgt} by {@code row}: both ways through {@code
   * Telescope.mapper}, or forward only through {@code Telescope.mapperForward}.
   */
  private static JavaFileObject src(
    final String declaration,
    final String body,
    final String row,
    final boolean forwardOnly
  ) {
    return ProcessorHarness.source(
      "demo.Src",
      """
      package demo;
      import static io.github.eschizoid.telescope.mapping.Mapping.*;
      import io.github.eschizoid.telescope.Telescope;
      import io.github.eschizoid.telescope.annotations.Bridge;
      import io.github.eschizoid.telescope.conversion.ForwardMapper;
      import io.github.eschizoid.telescope.conversion.Mapper;
      import java.util.List;
      %s {
        @Bridge
        public static final %s<Src, Tgt> MAPPER = Telescope.%s(Src.class, Tgt.class, %s);
      %s}
      """.formatted(
          declaration,
          forwardOnly ? "ForwardMapper" : "Mapper",
          forwardOnly ? "mapperForward" : "mapper",
          row,
          body
        )
    );
  }

  /** As {@link #src}, for a record of {@code components} converting both ways. */
  private static JavaFileObject src(final String components, final String row) {
    return src("public record Src(" + components + ")", "", row, false);
  }

  /**
   * A conversion whose two type arguments differ, so the two directions are not interchangeable.
   */
  private static JavaFileObject wideningFn() {
    return ProcessorHarness.source(
      "demo.WideFn",
      """
      package demo;
      import io.github.eschizoid.telescope.conversion.BridgeFn;
      public final class WideFn implements BridgeFn<Object, String> {
        public static final WideFn INSTANCE = new WideFn();
        @Override public String forward(final Object o) { return String.valueOf(o); }
        @Override public Number backward(final String s) { return s.length(); }
      }
      """
    );
  }

  /** {@code row} converting {@code label} of a pair of records through {@code WideFn}. */
  private static JavaFileObject[] pair(final String row, final boolean forwardOnly) {
    return new JavaFileObject[] {
      wideningFn(),
      src("public record Src(String label)", "", row, forwardOnly),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String label) {}"),
    };
  }

  @Test
  @DisplayName("a conversion that fits forward but not backward is reported, not emitted")
  void backwardMismatchIsReported() {
    // String reaches Object, and String reaches String, so forward fits. Backward has to store the
    // Number that backward() returns into a String field, which it cannot, and that only shows up
    // once the generated file is attributed.
    final var compilation = compile(pair("to(Src::label, Tgt::label, WideFn.class)", false));

    assertFalse(compilation.success(), "a row whose backward cannot compile must be refused");
    assertTrue(
      compilation.hasError("does not fit backward"),
      () -> "the diagnostic should name the failing direction: " + compilation.errorMessages()
    );
    assertTrue(
      compilation.hasError("toOneWay(...) in a Telescope.mapperForward(...) mapper"),
      () -> "and the remedy, since the row may have been meant one-way: " + compilation.errorMessages()
    );
    assertFalse(
      compilation.errorMessages().contains("cannot be converted to"),
      () -> "the raw error inside the generated file is what this replaces: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("the same conversion forward only, as the refusal advises, is accepted, since no backward is emitted")
  void forwardOnlyIsAccepted() {
    // The control, and the reason the check cannot simply demand both directions always fit: a
    // row that emits no backward has no backward to typecheck.
    final var compilation = compile(pair("toOneWay(Src::label, Tgt::label, WideFn.INSTANCE::forward)", true));

    assertTrue(compilation.success(), () -> "forward-only rows emit no backward: " + compilation.errorMessages());
  }

  @Test
  @DisplayName("a covariant override is accepted, because the call site binds to it and not the interface")
  void covariantBackwardIsAccepted() {
    // backward is declared to return Number by the interface and Integer by the class. Java binds
    // the call to the class's method, so the emitted assignment into an Integer slot is well
    // typed — and a check that reads the interface's arguments refuses a program that compiles.
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.CovBackFn",
        """
        package demo;
        import io.github.eschizoid.telescope.conversion.BridgeFn;
        public final class CovBackFn implements BridgeFn<Number, String> {
          @Override public String forward(final Number n) { return String.valueOf(n); }
          @Override public Integer backward(final String s) { return Integer.valueOf(s); }
        }
        """
      ),
      src("Integer v", "to(Src::v, Tgt::v, CovBackFn.class)"),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String v) {}")
    );

    assertTrue(compilation.success(), () -> "a covariant override round-trips: " + compilation.errorMessages());
  }

  @Test
  @DisplayName("a covariant forward override is accepted too, not only a covariant backward one")
  void covariantForwardIsAccepted() {
    // The mirror of the case above, and one the check refused before either direction was resolved
    // from the class rather than from the interface.
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.CovFwdFn",
        """
        package demo;
        import io.github.eschizoid.telescope.conversion.BridgeFn;
        public final class CovFwdFn implements BridgeFn<String, CharSequence> {
          @Override public String forward(final String s) { return s; }
          @Override public String backward(final CharSequence c) { return c.toString(); }
        }
        """
      ),
      src("String v", "to(Src::v, Tgt::v, CovFwdFn.class)"),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String v) {}")
    );

    assertTrue(compilation.success(), () -> "the class narrows both returns: " + compilation.errorMessages());
  }

  @Test
  @DisplayName("a private overload is not selected, because the generated bridge cannot call it")
  void privateOverloadIsNotSelected() {
    // A more specific overload that happens to be private. Resolution sees it, javac binding the
    // generated call from another class does not, so selecting it declares the row fits on the
    // strength of a method that will never run -- and the public one that does run then fails.
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.PrivFn",
        """
        package demo;
        import io.github.eschizoid.telescope.conversion.BridgeFn;
        public final class PrivFn implements BridgeFn<Object, Object> {
          @Override public Number forward(final Object o) { return 0; }
          private String forward(final String s) { return s; }
          @Override public String backward(final Object o) { return String.valueOf(o); }
        }
        """
      ),
      src("String v", "to(Src::v, Tgt::v, PrivFn.class)"),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String v) {}")
    );

    assertFalse(compilation.success(), "the callable overload returns a Number, which does not fit");
    assertFalse(
      compilation.errorMessages().contains("cannot be converted to"),
      () -> "it must be a diagnostic, not a raw error in the generated file: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("concrete methods inherited from a generic base are read as the subclass fixed them")
  void inheritedGenericMembersAreSubstituted() {
    // backward is declared to return T by the base. On its own that erases to Object and fits
    // nothing; substituted for what the subclass fixed T to, it returns Integer and fits.
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.GenBase",
        """
        package demo;
        import io.github.eschizoid.telescope.conversion.BridgeFn;
        public abstract class GenBase<T> implements BridgeFn<T, String> {
          @Override public String forward(final T t) { return String.valueOf(t); }
          @Override public T backward(final String s) { return null; }
        }
        """
      ),
      ProcessorHarness.source("demo.GenSub", "package demo; public final class GenSub extends GenBase<Integer> {}"),
      src("Integer v", "to(Src::v, Tgt::v, GenSub.class)"),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String v) {}")
    );

    assertTrue(compilation.success(), () -> "the subclass fixes T to Integer: " + compilation.errorMessages());
  }

  /**
   * The interface declares {@code forward(CharSequence)}, so only that overload carries the
   * annotation.
   */
  private static String forwardDecl(final String param) {
    final var over = "CharSequence".equals(param) ? "@Override " : "";
    return over + "public String forward(final " + param + " a) { return String.valueOf(a); }";
  }

  @ParameterizedTest(name = "declared {0} first")
  @ValueSource(strings = { "CharSequence", "String" })
  @DisplayName("an overload pair resolves the same way whichever order it is declared in")
  void overloadResolutionIsOrderIndependent(final String firstParam) {
    // Two overloads that are BOTH applicable to a String argument, returning the same type. That
    // is what makes this exercise the comparison rather than the applicability filter: a pair
    // where only one candidate applies never reaches the comparison at all.
    //
    // Choosing by return type compares them equal, so whichever is visited last wins and the
    // verdict flips with declaration order. Choosing by most-specific parameter -- what javac does
    // at the call site -- does not.
    final var second = "CharSequence".equals(firstParam) ? "Integer" : "CharSequence";
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.TwoFn",
        """
        package demo;
        import io.github.eschizoid.telescope.conversion.BridgeFn;
        public final class TwoFn implements BridgeFn<CharSequence, String> {
          %s
          %s
          @Override public String backward(final String s) { return s; }
        }
        """.formatted(forwardDecl(firstParam), forwardDecl(second))
      ),
      src("String v", "to(Src::v, Tgt::v, TwoFn.class)"),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String v) {}")
    );

    assertTrue(compilation.success(), () -> "order must not decide the verdict: " + compilation.errorMessages());
  }

  @Test
  @DisplayName("an overload the bridge cannot reach across packages is not selected either")
  void unreachableAcrossPackagesIsNotSelected() {
    // The bridge is emitted beside the source and subclasses nothing, so a package-private member
    // of a conversion in another package is as unbindable as a private one. Choosing it would
    // refuse a pair javac resolves to the public overload, which fits.
    final var compilation = compile(
      ProcessorHarness.source(
        "other.PkgFn",
        """
        package other;
        import io.github.eschizoid.telescope.conversion.BridgeFn;
        public final class PkgFn implements BridgeFn<Object, String> {
          @Override public String forward(final Object o) { return String.valueOf(o); }
          Integer forward(final String s) { return 1; }
          @Override public String backward(final String s) { return s; }
        }
        """
      ),
      src("String v", "to(Src::v, Tgt::v, other.PkgFn.class)"),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String v) {}")
    );

    assertTrue(compilation.success(), () -> "the reachable overload fits: " + compilation.errorMessages());
  }

  @Test
  @DisplayName("the same overload in the bridge's own package is reachable, so it is selected")
  void reachableInTheSamePackageIsSelected() {
    // The control for the row above. Package-private is about which package, not about the
    // modifier alone — a check that ignored every package-private member would pass that test
    // while missing a member javac really does bind.
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.PkgFn",
        """
        package demo;
        import io.github.eschizoid.telescope.conversion.BridgeFn;
        public final class PkgFn implements BridgeFn<Object, String> {
          @Override public String forward(final Object o) { return String.valueOf(o); }
          Integer forward(final String s) { return 1; }
          @Override public String backward(final String s) { return s; }
        }
        """
      ),
      src("String v", "to(Src::v, Tgt::v, PkgFn.class)"),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String v) {}")
    );

    assertFalse(compilation.success(), "javac binds the narrower overload here, and Integer does not fit");
  }

  @Test
  @DisplayName("the diagnostic names the signature the call site binds to, not the interface's")
  void diagnosticNamesTheResolvedSignature() {
    // An overload or an override means the bound signature and the interface's parameterisation
    // are different things. A message reconstructing the latter prints types that all fit each
    // other while the row is refused, which is true of the class and useless about the refusal --
    // and leaves the member the author has to edit unnamed.
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.OverloadedFn",
        """
        package demo;
        import io.github.eschizoid.telescope.conversion.BridgeFn;
        public final class OverloadedFn implements BridgeFn<Object, String> {
          @Override public String forward(final Object o) { return String.valueOf(o); }
          public Integer forward(final String s) { return 1; }
          @Override public Object backward(final String s) { return s; }
        }
        """
      ),
      src("String v", "to(Src::v, Tgt::v, OverloadedFn.class)"),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String v) {}")
    );

    assertFalse(compilation.success(), "the bound overload returns Integer, which the String slot refuses");
    assertTrue(
      compilation.hasError("forward resolves to (java.lang.String) -> java.lang.Integer"),
      () -> "the bound signature is what the author has to change: " + compilation.errorMessages()
    );
    assertFalse(
      compilation.errorMessages().contains("implements BridgeFn<java.lang.Object, java.lang.String>"),
      () -> "and the interface's parameterisation fits, so naming it explains nothing: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("a conversion that fits neither direction still names forward, the first thing to fix")
  void forwardMismatchStillNamesForward() {
    // A pair where forward fails too: an Integer binds the overload returning an Integer, which
    // the String slot refuses, and backward returns a Number, which the Integer slot refuses. The
    // message should lead with forward rather than report the second failure, since fixing
    // forward is what the author has to do first.
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.BothFn",
        """
        package demo;
        import io.github.eschizoid.telescope.conversion.BridgeFn;
        public final class BothFn implements BridgeFn<Object, String> {
          @Override public String forward(final Object o) { return String.valueOf(o); }
          public Integer forward(final Integer i) { return i; }
          @Override public Number backward(final String s) { return s.length(); }
        }
        """
      ),
      src("Integer label", "to(Src::label, Tgt::label, BothFn.class)"),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String label) {}")
    );

    assertFalse(compilation.success(), "neither direction fits");
    assertTrue(
      compilation.hasError("does not fit forward"),
      () -> "forward is the half to name first: " + compilation.errorMessages()
    );
  }

  /**
   * A bean pair whose {@code value} is a list of {@code Integer} on the source and of {@code
   * Number} on the target, converted forward by {@code demo.ListFn}, which takes any collection of
   * numbers and returns {@code body}, typed {@code result}.
   */
  private static JavaFileObject[] listPair(final String result, final String body) {
    return new JavaFileObject[] {
      ProcessorHarness.source(
        "demo.ListFn",
        """
        package demo;
        import io.github.eschizoid.telescope.conversion.BridgeFn;
        import java.util.Collection;
        public final class ListFn implements BridgeFn<Collection<? extends Number>, %1$s> {
          public static final ListFn INSTANCE = new ListFn();
          @Override public %1$s forward(final Collection<? extends Number> a) { return %2$s; }
          @Override public Collection<? extends Number> backward(final %1$s b) { return b; }
        }
        """.formatted(result, body)
      ),
      src(
        "public class Src",
        """
          private List<Integer> value;
          public List<Integer> getValue() { return value; }
          public void setValue(final List<Integer> value) { this.value = value; }
        """,
        "toOneWay(Src::getValue, Tgt::getValue, ListFn.INSTANCE::forward)",
        true
      ),
      ProcessorHarness.source(
        "demo.Tgt",
        """
        package demo;
        import java.util.List;
        public class Tgt {
          private List<Number> value;
          public List<Number> getValue() { return value; }
          public void setValue(final List<Number> value) { this.value = value; }
        }
        """
      ),
    };
  }

  @Test
  @DisplayName("a conversion result whose type arguments are not the member's own is refused by name")
  void aResultWhoseArgumentsAreNotTheMembersIsRefused() {
    // A result is written as it is, and nothing shows that only the target holds it: this one is
    // the source's own list, which a write of a Double through the target's List<Number> would
    // reach. Its type arguments have to be the member's, as a cast to the member's type needs.
    final var compilation = compile(listPair("java.util.Collection<? extends Number>", "a"));

    assertFalse(compilation.success(), "a result held as a Collection<? extends Number> is no List<Number>");
    assertTrue(
      compilation.hasError(
        "a converting row of 'value' does not fit forward: demo.SrcToTgtBridgeValueFn's forward resolves to" +
          " (java.util.List<java.lang.Integer>) -> java.util.Collection<? extends java.lang.Number>, which does not" +
          " carry java.util.List<java.lang.Integer> -> java.util.List<java.lang.Number>"
      ),
      () -> "the diagnostic should name the result and the member: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("a conversion result of a wider class with the member's own type arguments is written after a check")
  void aWiderResultWithTheMembersArgumentsIsWritten() {
    final var compilation = compile(listPair("java.util.Collection<Number>", "new java.util.ArrayList<Number>(a)"));

    assertTrue(compilation.success(), () -> "the result's arguments are the member's: " + compilation.errorMessages());
  }
}
