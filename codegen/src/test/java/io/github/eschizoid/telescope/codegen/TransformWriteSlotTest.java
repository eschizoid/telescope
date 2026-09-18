package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A field is read through its getter and written through whichever member the rebuild picks, and
 * those are different types often enough to matter. A setter may be declared wider than its getter,
 * a builder method narrower, and a name-matched constructor parameter either — so judging a
 * transform against the declaration asks about a member the emission may never write through.
 *
 * <p>Which member that is depends on the rung the rebuild stops at, and the rungs disagree: the
 * same target can carry a wide setter the emission ignores because a builder outranks it. That is
 * why the answer cannot come from the setter alone, and why a previous attempt at this traded a
 * conservative refusal for an emission that did not compile.
 *
 * <p>These compile through the full pipeline. A wrong verdict in the accepting direction is only
 * visible once the generated file is attributed, which the processing-only harness never does.
 */
class TransformWriteSlotTest {

  /** Widens on the way out: reads {@code String}, hands back {@code Object}. */
  private static final String WIDENING_FN = """
    package demo;
    import io.github.eschizoid.telescope.conversion.BridgeFn;
    public final class Fn implements BridgeFn<String, Object> {
      @Override public Object forward(final String s) { return s; }
      @Override public String backward(final Object o) { return String.valueOf(o); }
    }
    """;

  private static ProcessorHarness.Compilation compile(final String targetBody) {
    return ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      new JavaFileObject[] {
        ProcessorHarness.source("demo.Fn", WIDENING_FN),
        ProcessorHarness.source(
          "demo.Src",
          """
          package demo;
          import io.github.eschizoid.telescope.annotations.Bridge;
          import io.github.eschizoid.telescope.annotations.Transform;
          @Bridge(value = demo.Tgt.class, transforms = {
            @Transform(field = "v", using = demo.Fn.class, forwardOnly = true)
          })
          public record Src(String v) {}
          """
        ),
        ProcessorHarness.source("demo.Tgt", "package demo;\n" + targetBody),
      }
    );
  }

  private static void assertAccepted(final ProcessorHarness.Compilation c) {
    assertTrue(c.success(), () -> "the emitted write would have taken it: " + c.errorMessages());
  }

  private static void assertRefusedByTheCheck(final ProcessorHarness.Compilation c) {
    assertFalse(c.success(), "this write cannot compile, so the row has to be refused");
    assertTrue(
      c.hasError("@Transform field=\"v\""),
      () -> "and refused here, not by javac inside the generated file: " + c.errorMessages()
    );
  }

  @Test
  @DisplayName("a setter wider than its getter takes a value the getter does not name")
  void aWiderSetterIsTheSlot() {
    // Nothing else can rebuild this target, so the emission writes `out.setV(...)` and that
    // parameter is what the value has to reach. The getter says String and is not consulted.
    assertAccepted(
      compile(
        """
        public class Tgt {
          private String v;
          public Tgt() {}
          public String getV() { return v; }
          public void setV(final Object v) { this.v = String.valueOf(v); }
        }
        """
      )
    );
  }

  @Test
  @DisplayName("a builder outranks a setter, so a wide setter it never calls decides nothing")
  void aBuilderOutranksAWiderSetter() {
    // The case that makes this a question about the rebuild rather than about setters. Both rungs
    // apply here -- there is a public no-argument constructor and a setter that would take the
    // value -- and the builder is the one the emission uses, so its narrower method is the slot.
    //
    // The public constructor is what makes this row about ranking. With a private one the setters
    // rung is simply inapplicable, and the row would hold whichever rung came first, proving only
    // that a builder can be used rather than that it outranks.
    assertRefusedByTheCheck(
      compile(
        """
        public class Tgt {
          private String v;
          public Tgt() {}
          public String getV() { return v; }
          public void setV(final Object v) { this.v = String.valueOf(v); }
          public static Builder builder() { return new Builder(); }
          public static final class Builder {
            private final Tgt held = new Tgt();
            public Builder v(final String v) { held.v = v; return this; }
            public Tgt build() { return held; }
          }
        }
        """
      )
    );
  }

  @Test
  @DisplayName("a builder method wider than its getter is the slot where nothing else rebuilds")
  void aWiderBuilderMethodIsTheSlot() {
    // The builder rung's own version of the first row. No setters at all, so the builder is what
    // the emission writes through, and its parameter is wider than the getter it corresponds to.
    assertAccepted(
      compile(
        """
        public class Tgt {
          private String v;
          private Tgt() {}
          public String getV() { return v; }
          public static Builder builder() { return new Builder(); }
          public static final class Builder {
            private final Tgt held = new Tgt();
            public Builder v(final Object v) { held.v = String.valueOf(v); return this; }
            public Tgt build() { return held; }
          }
        }
        """
      )
    );
  }

  @Test
  @DisplayName("a name-matched constructor parameter wider than its getter is the slot")
  void aWiderConstructorParameterIsTheSlot() {
    // The constructor rung, which outranks both of the others. Its parameter is what the emitted
    // `new Tgt(...)` has to accept.
    assertAccepted(
      compile(
        """
        public class Tgt {
          private final String v;
          public Tgt(final Object v) { this.v = String.valueOf(v); }
          public String getV() { return v; }
        }
        """
      )
    );
  }

  @Test
  @DisplayName("two setter overloads give the same answer whichever is declared first")
  void setterOverloadsAreOrderIndependent() {
    // The emission writes the setter's name and lets Java bind the overload, so the two orderings
    // below produce identical text. Taking the first of that name made the verdict depend on
    // something the generated file does not.
    final var stringFirst = """
      public class Tgt {
        private String v;
        public Tgt() {}
        public String getV() { return v; }
        public void setV(final String v) { this.v = v; }
        public void setV(final Object v) { this.v = String.valueOf(v); }
      }
      """;
    final var objectFirst = """
      public class Tgt {
        private String v;
        public Tgt() {}
        public String getV() { return v; }
        public void setV(final Object v) { this.v = String.valueOf(v); }
        public void setV(final String v) { this.v = v; }
      }
      """;

    assertAccepted(compile(stringFirst));
    assertAccepted(compile(objectFirst));
  }

  @Test
  @DisplayName("a setter too narrow for the value is still refused, by the check")
  void aNarrowSetterIsStillRefused() {
    // The control for every accepting row above. A slot that genuinely cannot hold the value has to
    // be refused here, where the diagnostic names the row, rather than by javac inside a file the
    // author never wrote.
    assertRefusedByTheCheck(
      compile(
        """
        public class Tgt {
          private String v;
          public Tgt() {}
          public String getV() { return v; }
          public void setV(final String v) { this.v = v; }
        }
        """
      )
    );
  }

  @Test
  @DisplayName("a record component is its own slot, as it always was")
  void aRecordComponentIsItsOwnSlot() {
    // The second control. A record is written through its canonical constructor, whose parameters
    // are its components -- so for records the declaration and the slot are the same type, and
    // nothing here should have moved.
    assertAccepted(compile("public record Tgt(Object v) {}"));
    assertRefusedByTheCheck(compile("public record Tgt(String v) {}"));
  }
}
