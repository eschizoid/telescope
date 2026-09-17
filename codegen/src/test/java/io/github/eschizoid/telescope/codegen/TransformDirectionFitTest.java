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

  /** A transform whose two type arguments differ, so the two directions are not interchangeable. */
  private static JavaFileObject wideningFn() {
    return ProcessorHarness.source(
      "demo.WideFn",
      """
      package demo;
      import io.github.eschizoid.telescope.conversion.BridgeFn;
      public final class WideFn implements BridgeFn<CharSequence, String> {
        @Override public String forward(final CharSequence c) { return c.toString(); }
        @Override public CharSequence backward(final String s) { return s; }
      }
      """
    );
  }

  private static JavaFileObject[] pair(final String extraTransformArgs) {
    return new JavaFileObject[] {
      wideningFn(),
      ProcessorHarness.source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Transform;
        @Bridge(value = demo.Tgt.class, transforms = {
          @Transform(field = "label", using = demo.WideFn.class%s)
        })
        public record Src(String label) {}
        """.formatted(extraTransformArgs)
      ),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String label) {}"),
    };
  }

  @Test
  @DisplayName("a transform that fits forward but not backward is reported, not emitted")
  void backwardMismatchIsReported() {
    // String reaches CharSequence, and String reaches String, so forward fits and the old check
    // was satisfied. Backward has to store the CharSequence that backward() returns into a String
    // field, which it cannot, and that only shows up once the generated file is attributed.
    final var compilation = compile(pair(""));

    assertFalse(compilation.success(), "a row whose backward cannot compile must be refused");
    assertTrue(
      compilation.hasError("does not fit backward"),
      () -> "the diagnostic should name the failing direction: " + compilation.errorMessages()
    );
    assertTrue(
      compilation.hasError("forwardOnly"),
      () -> "and the remedy, since the row may have been meant one-way: " + compilation.errorMessages()
    );
    assertFalse(
      compilation.errorMessages().contains("cannot be converted to"),
      () -> "the raw error inside the generated file is what this replaces: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("the same transform declared forward-only is accepted, since no backward is emitted")
  void forwardOnlyIsAccepted() {
    // The control, and the reason the check cannot simply demand both directions always fit: a
    // row that emits no backward has no backward to typecheck.
    final var compilation = compile(pair(", forwardOnly = true"));

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
      ProcessorHarness.source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Transform;
        @Bridge(value = demo.Tgt.class, transforms = {
          @Transform(field = "v", using = demo.CovBackFn.class)
        })
        public record Src(Integer v) {}
        """
      ),
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
      ProcessorHarness.source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Transform;
        @Bridge(value = demo.Tgt.class, transforms = { @Transform(field = "v", using = demo.CovFwdFn.class) })
        public record Src(String v) {}
        """
      ),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String v) {}")
    );

    assertTrue(compilation.success(), () -> "the class narrows both returns: " + compilation.errorMessages());
  }

  @Test
  @DisplayName("a non-generic transform keeps its type arguments, so a mismatched one is still caught")
  void nonGenericTransformIsNotErased() {
    // The emitter instantiates a using class raw only when it is generic. Erasing a non-generic
    // one's signature throws away the parameterisation the call site keeps, which lets a pair
    // through that then fails inside the generated file -- worse than not checking at all, since
    // the diagnostic that would have named it is the thing being skipped.
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.ListInv",
        """
        package demo;
        import io.github.eschizoid.telescope.conversion.BridgeFn;
        import java.util.List;
        public final class ListInv implements BridgeFn<List<String>, List<Integer>> {
          @Override public List<Integer> forward(final List<String> in) { return List.of(); }
          @Override public List<String> backward(final List<Integer> in) { return List.of(); }
        }
        """
      ),
      ProcessorHarness.source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Transform;
        @Bridge(value = demo.Tgt.class, transforms = { @Transform(field = "v", using = demo.ListInv.class) })
        public record Src(java.util.List<Long> v) {}
        """
      ),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(java.util.List<Double> v) {}")
    );

    assertFalse(compilation.success(), "List<Long> does not fit List<String>");
    assertFalse(
      compilation.errorMessages().contains("cannot be converted to"),
      () -> "it must be a diagnostic, not a raw error in the generated file: " + compilation.errorMessages()
    );
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
          @Override public Object forward(final Object o) { return o; }
          private String forward(final String s) { return s; }
          @Override public String backward(final Object o) { return String.valueOf(o); }
        }
        """
      ),
      ProcessorHarness.source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Transform;
        @Bridge(value = demo.Tgt.class, transforms = { @Transform(field = "v", using = demo.PrivFn.class) })
        public record Src(String v) {}
        """
      ),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String v) {}")
    );

    assertFalse(compilation.success(), "the callable overload returns Object, which does not fit");
    assertFalse(
      compilation.errorMessages().contains("cannot be converted to"),
      () -> "it must be a diagnostic, not a raw error in the generated file: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("a mismatch on the return side alone is caught, not only one on the parameter")
  void returnSideMismatchIsCaught() {
    // The sibling of the erasure case above. That one mismatches on the parameter, so a check that
    // erased only the return would still pass it — this one can fail only if the return is
    // compared with its type arguments intact.
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.RetFn",
        """
        package demo;
        import io.github.eschizoid.telescope.conversion.BridgeFn;
        import java.util.List;
        public final class RetFn implements BridgeFn<String, List<Integer>> {
          @Override public List<Integer> forward(final String s) { return List.of(); }
          @Override public String backward(final List<Integer> in) { return ""; }
        }
        """
      ),
      ProcessorHarness.source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Transform;
        @Bridge(value = demo.Tgt.class, transforms = { @Transform(field = "v", using = demo.RetFn.class) })
        public record Src(String v) {}
        """
      ),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(java.util.List<String> v) {}")
    );

    assertFalse(compilation.success(), "List<Integer> does not fit a List<String> slot");
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
      ProcessorHarness.source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Transform;
        @Bridge(value = demo.Tgt.class, transforms = { @Transform(field = "v", using = demo.GenSub.class) })
        public record Src(Integer v) {}
        """
      ),
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
          @Override public CharSequence backward(final String s) { return s; }
        }
        """.formatted(forwardDecl(firstParam), forwardDecl(second))
      ),
      ProcessorHarness.source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Transform;
        @Bridge(value = demo.Tgt.class, transforms = {
          @Transform(field = "v", using = demo.TwoFn.class, forwardOnly = true)
        })
        public record Src(String v) {}
        """
      ),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String v) {}")
    );

    assertTrue(compilation.success(), () -> "order must not decide the verdict: " + compilation.errorMessages());
  }

  private static JavaFileObject rawFn(final String forwardReturn) {
    return ProcessorHarness.source(
      "demo.RawFn",
      """
      package demo;
      import io.github.eschizoid.telescope.conversion.BridgeFn;
      @SuppressWarnings("rawtypes")
      public final class RawFn implements BridgeFn {
        @Override public %s forward(final Object o) { return String.valueOf(o); }
        @Override public String backward(final Object o) { return String.valueOf(o); }
      }
      """.formatted(forwardReturn)
    );
  }

  private static JavaFileObject[] rawPair(final String forwardReturn) {
    return new JavaFileObject[] {
      rawFn(forwardReturn),
      ProcessorHarness.source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Transform;
        @Bridge(value = demo.Tgt.class, transforms = { @Transform(field = "v", using = demo.RawFn.class) })
        public record Src(String v) {}
        """
      ),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(String v) {}"),
    };
  }

  @Test
  @DisplayName("a raw implementation is checked through the methods it declares, having no arguments to" + " read")
  void rawImplementationIsStillChecked() {
    // The one shape with no type arguments to fall back on was also the one shape not checked at
    // all, so a mismatch reached the generated file. The class still declares the two methods the
    // call sites bind to, and those are what the check compares.
    final var compilation = compile(rawPair("Object"));

    assertFalse(compilation.success(), "Object does not fit a String slot");
    assertTrue(
      compilation.hasError("does not fit forward"),
      () -> "the diagnostic should name the direction: " + compilation.errorMessages()
    );
    assertFalse(
      compilation.errorMessages().contains("cannot be converted to"),
      () -> "and replace the raw error, not accompany it: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("a raw implementation whose methods do fit is accepted, not refused for being raw")
  void rawImplementationThatFitsIsAccepted() {
    // The control. Rawness is not the defect — an unchecked conversion is. A class declaring
    // String forward(Object) genuinely produces what a String slot takes, and a check that
    // refused every raw implementation would pass the test above while breaking this.
    final var compilation = compile(rawPair("String"));

    assertTrue(compilation.success(), () -> "its declared methods fit: " + compilation.errorMessages());
  }

  @Test
  @DisplayName("a transform that fits neither direction still names forward, the first thing to fix")
  void forwardMismatchStillNamesForward() {
    // A pair where forward fails too. The message should lead with forward rather than report the
    // second failure, since fixing forward is what the author has to do first.
    final var compilation = compile(
      wideningFn(),
      ProcessorHarness.source(
        "demo.Src",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Transform;
        @Bridge(value = demo.Tgt.class, transforms = {
          @Transform(field = "label", using = demo.WideFn.class)
        })
        public record Src(Integer label) {}
        """
      ),
      ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(Integer label) {}")
    );

    assertFalse(compilation.success(), "neither direction fits");
    assertTrue(
      compilation.hasError("does not fit forward"),
      () -> "forward is the half to name first: " + compilation.errorMessages()
    );
  }
}
