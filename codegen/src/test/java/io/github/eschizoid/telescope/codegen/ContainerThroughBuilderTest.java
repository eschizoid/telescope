package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A container declared as a type nothing allocatable is an instance of is still reachable when it
 * owns a static {@code builder()}. The reflective path binds that pair, so refusing it here made
 * one declaration convert under {@code mapper(...)} and fail to compile under {@code @Bridge}.
 *
 * <p>What admits the route is the builder's own {@code build()} producing the declared type. A
 * builder that hands back something wider is the shape whose failure would land in generated code
 * rather than in a diagnostic, so it stays refused — as does a type with no builder at all.
 *
 * <p>The two accepting cases reach the allocation by different routes. Elements that convert are
 * filled by an element-bridging helper; elements that pass through take the self-contained one, and
 * a route wired into only one of those is what makes a container compile here and throw there.
 *
 * <p>Every case compiles through the full pipeline: what is under test is an allocation expression,
 * and {@code -proc:only} completes declarations without ever attributing a method body.
 */
class ContainerThroughBuilderTest {

  /**
   * One container family. {@code rawSuper} is what each fixture extends and, separately, what a
   * builder hands back when the point of the case is that it hands back too much; {@code plainArgs}
   * names an element type no conversion touches.
   */
  private record Family(
    String label,
    String typeParams,
    String rawSuper,
    String rawArgs,
    String srcArgs,
    String tgtArgs,
    String plainIface,
    String plainArgs
  ) {
    @Override
    public String toString() {
      return label;
    }

    String container(final String prefix, final String args) {
      return "demo." + prefix + label + "<" + args + ">";
    }
  }

  private static Stream<Family> families() {
    return Stream.of(
      new Family("List", "E", "java.util.ArrayList", "Object", "demo.SA", "demo.SB", "java.util.List", "String"),
      new Family("Set", "E", "java.util.LinkedHashSet", "Object", "demo.SA", "demo.SB", "java.util.Set", "String"),
      new Family(
        "Map",
        "K, V",
        "java.util.HashMap",
        "Object, Object",
        "String, demo.SA",
        "String, demo.SB",
        "java.util.Map",
        "String, String"
      )
    );
  }

  private static Compilation compile(final List<JavaFileObject> sources) {
    return ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      sources.toArray(JavaFileObject[]::new)
    );
  }

  @SafeVarargs
  private static List<JavaFileObject> concat(final List<JavaFileObject>... parts) {
    final var out = new ArrayList<JavaFileObject>();
    for (final var part : parts) out.addAll(part);
    return out;
  }

  /** The two element records a bridged pairing converts between. */
  private static List<JavaFileObject> elements() {
    return List.of(
      ProcessorHarness.source("demo.SA", "package demo; public record SA(String v) {}"),
      ProcessorHarness.source("demo.SB", "package demo; public record SB(String v) {}")
    );
  }

  /**
   * The adopter shape: a container reachable only through its own builder. Abstract, so the family
   * default stands in for it and is not of its type, which leaves {@code build()} as the only way
   * to a value the field can hold.
   */
  private static List<JavaFileObject> buildable(final Family family) {
    return List.of(
      ProcessorHarness.source(
        "demo.Built" + family.label(),
        """
        package demo;
        public abstract class Built%1$s<%2$s> extends %3$s<%2$s> {
          private static final long serialVersionUID = 1L;
          protected Built%1$s() {}
          public static Builder builder() { return new Builder(); }
          public static final class Builder {
            public Built%1$s<%4$s> build() { return new Built%1$sImpl<>(); }
          }
        }
        """.formatted(family.label(), family.typeParams(), family.rawSuper(), family.rawArgs())
      ),
      ProcessorHarness.source(
        "demo.Built" + family.label() + "Impl",
        """
        package demo;
        public final class Built%1$sImpl<%2$s> extends Built%1$s<%2$s> {
          private static final long serialVersionUID = 1L;
          public Built%1$sImpl() {}
        }
        """.formatted(family.label(), family.typeParams())
      )
    );
  }

  /** The bridged pair whose single field carries the container under test. */
  private static List<JavaFileObject> pair(final String srcField, final String tgtField) {
    return List.of(
      ProcessorHarness.source(
        "demo.BSrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.BDst.class)
        public record BSrc(%s items) {}
        """.formatted(srcField)
      ),
      ProcessorHarness.source("demo.BDst", "package demo; public record BDst(%s items) {}".formatted(tgtField))
    );
  }

  private static void assertAllocatesThroughBuilder(final Family family, final Compilation compilation) {
    assertTrue(compilation.success(), () -> family + " should bridge: " + compilation.errorMessages());
    final var bridge = compilation.generated().get("demo.BSrcBridge");
    final var call = "demo.Built" + family.label() + ".builder().build()";
    assertTrue(
      bridge != null && bridge.contains(call),
      () -> family + " should allocate through " + call + "; saw " + bridge
    );
  }

  private static void assertRefused(final Family family, final Compilation compilation) {
    assertFalse(compilation.success(), () -> family + " should be refused; it compiled");
    assertTrue(
      compilation.errorMessages().contains("which telescope cannot construct"),
      () -> family + " should be refused by name; saw " + compilation.errorMessages()
    );
  }

  @ParameterizedTest(name = "{0} through its builder, elements bridged")
  @MethodSource("families")
  @DisplayName("a container reached through a static builder() is allocated there when its elements convert")
  void bridgedElementsAllocateThroughTheBuilder(final Family family) {
    assertAllocatesThroughBuilder(
      family,
      compile(
        concat(
          elements(),
          buildable(family),
          pair(family.container("Built", family.srcArgs()), family.container("Built", family.tgtArgs()))
        )
      )
    );
  }

  @ParameterizedTest(name = "{0} through its builder, elements unchanged")
  @MethodSource("families")
  @DisplayName("a container reached through a static builder() is allocated there when its elements pass" + " through")
  void identityElementsAllocateThroughTheBuilder(final Family family) {
    // The two sides have to differ in shape for anything to be allocated at all: one declaration on
    // both sides of an identity element is handed over by reference, which allocates nothing and
    // would pass this assertion's negation just as happily.
    final var plainSide = family.plainIface() + "<" + family.plainArgs() + ">";
    assertAllocatesThroughBuilder(
      family,
      compile(concat(buildable(family), pair(plainSide, family.container("Built", family.plainArgs()))))
    );
  }

  @ParameterizedTest(name = "{0} concrete, with its constructor hidden")
  @MethodSource("families")
  @DisplayName("a concrete container that hides its constructor is reached through its builder")
  void aHiddenConstructorIsNotAnObstacle(final Family family) {
    // The accepted cases above are abstract, so both the allocation question and the route are
    // settled before a constructor is looked for. Here the type is instantiable in principle and
    // deliberately not by the caller, which is the whole of what a builder-owning container is.
    final var sealed = ProcessorHarness.source(
      "demo.Sealed" + family.label(),
      """
      package demo;
      public final class Sealed%1$s<%2$s> extends %3$s<%2$s> {
        private static final long serialVersionUID = 1L;
        private Sealed%1$s() {}
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
          public Sealed%1$s<%4$s> build() { return new Sealed%1$s<>(); }
        }
      }
      """.formatted(family.label(), family.typeParams(), family.rawSuper(), family.rawArgs())
    );
    final var compilation = compile(
      concat(
        elements(),
        List.of(sealed),
        pair(family.container("Sealed", family.srcArgs()), family.container("Sealed", family.tgtArgs()))
      )
    );

    assertTrue(compilation.success(), () -> family + " should bridge: " + compilation.errorMessages());
    final var bridge = compilation.generated().get("demo.BSrcBridge");
    final var call = "demo.Sealed" + family.label() + ".builder().build()";
    assertTrue(
      bridge != null && bridge.contains(call),
      () -> family + " should allocate through " + call + "; saw " + bridge
    );
  }

  @ParameterizedTest(name = "{0} declares the cast unchecked")
  @MethodSource("families")
  @DisplayName("the builder route's cast is suppressed where it is emitted, and only there")
  void theBuilderCastCarriesItsOwnSuppression(final Family family) {
    // Casting what build() hands back is unchecked by construction, so a consumer compiling with
    // -Werror would be failed by code they did not write. The control side is what keeps this from
    // being satisfied by a suppression emitted unconditionally.
    final var routed = compile(
      concat(
        elements(),
        buildable(family),
        pair(family.container("Built", family.srcArgs()), family.container("Built", family.tgtArgs()))
      )
    );
    assertTrue(routed.success(), () -> family + " should bridge: " + routed.errorMessages());
    assertTrue(
      routed.generated().get("demo.BSrcBridge").contains("@SuppressWarnings(\"unchecked\") final var out ="),
      () -> family + " should suppress the builder cast; saw " + routed.generated().get("demo.BSrcBridge")
    );

    final var plainSide = family.plainIface() + "<" + family.srcArgs() + ">";
    final var plainTarget = family.plainIface() + "<" + family.tgtArgs() + ">";
    final var unrouted = compile(concat(elements(), pair(plainSide, plainTarget)));
    assertTrue(unrouted.success(), () -> family + " control should bridge: " + unrouted.errorMessages());
    assertFalse(
      unrouted.generated().get("demo.BSrcBridge").contains("@SuppressWarnings"),
      () ->
        family + " allocates its own type and needs no suppression; saw " + unrouted.generated().get("demo.BSrcBridge")
    );
  }

  @ParameterizedTest(name = "{0} whose builder inherits build()")
  @MethodSource("families")
  @DisplayName("a builder that inherits build() from a base is as good a route as one that declares it")
  void anInheritedBuildIsStillARoute(final Family family) {
    // Where the method is written says nothing about what it produces, so a scan of declared
    // members alone refuses a shared builder base for its authorship.
    final var inherited = ProcessorHarness.source(
      "demo.Inherited" + family.label(),
      """
      package demo;
      public abstract class Inherited%1$s<%2$s> extends %3$s<%2$s> {
        private static final long serialVersionUID = 1L;
        protected Inherited%1$s() {}
        public abstract static class BaseBuilder {
          public Inherited%1$s<%4$s> build() { return new Inherited%1$sImpl<>(); }
        }
        public static final class Builder extends BaseBuilder {}
        public static Builder builder() { return new Builder(); }
      }
      """.formatted(family.label(), family.typeParams(), family.rawSuper(), family.rawArgs())
    );
    final var impl = ProcessorHarness.source(
      "demo.Inherited" + family.label() + "Impl",
      """
      package demo;
      public final class Inherited%1$sImpl<%2$s> extends Inherited%1$s<%2$s> {
        private static final long serialVersionUID = 1L;
        public Inherited%1$sImpl() {}
      }
      """.formatted(family.label(), family.typeParams())
    );
    final var compilation = compile(
      concat(
        elements(),
        List.of(inherited, impl),
        pair(family.container("Inherited", family.srcArgs()), family.container("Inherited", family.tgtArgs()))
      )
    );

    assertTrue(compilation.success(), () -> family + " should bridge: " + compilation.errorMessages());
    final var call = "demo.Inherited" + family.label() + ".builder().build()";
    assertTrue(
      compilation.generated().get("demo.BSrcBridge").contains(call),
      () -> family + " should allocate through " + call + "; saw " + compilation.generated().get("demo.BSrcBridge")
    );
  }

  @ParameterizedTest(name = "{0} whose build() is wider than the field")
  @MethodSource("families")
  @DisplayName("a builder whose build() does not produce the declared type is not a route to it")
  void aWiderBuildIsNotARoute(final Family family) {
    // build() hands back the supertype, which the field cannot hold. Calling it would compile and
    // fail the cast on the first conversion, which is the failure a plan-time refusal exists to
    // keep out of generated code.
    final var widened = ProcessorHarness.source(
      "demo.Wide" + family.label(),
      """
      package demo;
      public abstract class Wide%1$s<%2$s> extends %3$s<%2$s> {
        private static final long serialVersionUID = 1L;
        protected Wide%1$s() {}
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
          public %3$s<%4$s> build() { return new %3$s<>(); }
        }
      }
      """.formatted(family.label(), family.typeParams(), family.rawSuper(), family.rawArgs())
    );
    assertRefused(
      family,
      compile(
        concat(
          elements(),
          List.of(widened),
          pair(family.container("Wide", family.srcArgs()), family.container("Wide", family.tgtArgs()))
        )
      )
    );
  }

  @ParameterizedTest(name = "{0} with no builder at all")
  @MethodSource("families")
  @DisplayName("a container with no builder is refused exactly as before")
  void noBuilderIsStillRefused(final Family family) {
    final var plain = ProcessorHarness.source(
      "demo.Plain" + family.label(),
      """
      package demo;
      public abstract class Plain%1$s<%2$s> extends %3$s<%2$s> {
        private static final long serialVersionUID = 1L;
        protected Plain%1$s() {}
      }
      """.formatted(family.label(), family.typeParams(), family.rawSuper())
    );
    assertRefused(
      family,
      compile(
        concat(
          elements(),
          List.of(plain),
          pair(family.container("Plain", family.srcArgs()), family.container("Plain", family.tgtArgs()))
        )
      )
    );
  }
}
