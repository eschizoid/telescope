package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.codegen.hiddenbox.BoxedDst;
import io.github.eschizoid.telescope.codegen.hiddenpair.HiddenPairSrc;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A container class the bridge's package cannot name, which codegen refuses by name and the runtime
 * converts.
 *
 * <p>A bridge is emitted in its source's package and allocates the target's container by writing
 * its name. A public class nested in one that is not public is nameable only beside it, so a bridge
 * whose source lives elsewhere cannot write the allocation. The runtime binds the same constructor
 * through a private lookup, which asks nothing of the caller's package, so the paths part here by
 * construction: a limit of generated code rather than a disagreement either path could close.
 *
 * <p>The fixtures are real files, compiled with this test and so loadable by the runtime path. The
 * generated path compiles the same files again through the processor.
 */
class UnnameableContainerTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  private static final Path FIXTURES = Path.of("src/test/java/io/github/eschizoid/telescope/codegen");

  private static JavaFileObject fixture(final String pkg, final String name) throws IOException {
    final var code = Files.readString(FIXTURES.resolve(pkg).resolve(name + ".java"));
    return ProcessorHarness.source(PACKAGE + "." + pkg + "." + name, code);
  }

  private static List<JavaFileObject> box() throws IOException {
    return List.of(fixture("hiddenbox", "HiddenBox"), fixture("hiddenbox", "BoxedDst"));
  }

  private static ProcessorHarness.Compilation bridge(final List<JavaFileObject> sources) {
    return ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      sources.toArray(JavaFileObject[]::new)
    );
  }

  @Test
  @DisplayName("a container the bridge's package cannot name is refused by name, and the runtime converts it")
  void anUnnameableContainerIsRefusedWhereTheRuntimeConverts() throws IOException {
    final var sources = new ArrayList<>(box());
    sources.add(fixture("hiddenpair", "HiddenPairSrc"));
    final var generated = bridge(sources);

    assertFalse(generated.success(), "the bridge cannot be emitted");
    assertTrue(
      generated.hasError("cannot be named from package " + PACKAGE + ".hiddenpair"),
      () -> "refused by @Bridge rather than inside the generated file; saw " + generated.errorMessages()
    );
    assertFalse(
      generated.hasError("inaccessible"),
      () -> "no error from inside the generated file; saw " + generated.errorMessages()
    );

    final var converted = Telescope.mapper(HiddenPairSrc.class, BoxedDst.class).forward(
      new HiddenPairSrc(List.of("a", "b"))
    );
    assertEquals(PACKAGE + ".hiddenbox.HiddenBox$Bag", converted.items().getClass().getName());
    assertEquals(List.of("a", "b"), List.copyOf(converted.items()), "the runtime converts the same pair");
  }

  /**
   * Containers nested in a package-private class: {@code L} is reached only through a builder whose
   * type is public, {@code Bag} through its own constructor, and {@code Raw} is copied into when it
   * is used raw.
   */
  private static final List<JavaFileObject> HIDDEN = List.of(
    ProcessorHarness.source(
      "q6.Hid",
      """
      package q6;
      class Hid {
        public abstract static class L<E> extends java.util.ArrayList<E> {
          private static final long serialVersionUID = 1L;
          protected L() {}
          public static LB builder() { return new LB(); }
        }
        public static final class Impl<E> extends L<E> {
          private static final long serialVersionUID = 1L;
          public Impl() {}
        }
        public static class Bag<E> extends java.util.ArrayList<E> {
          private static final long serialVersionUID = 1L;
          public Bag() {}
        }
        public static class Raw<E> extends java.util.ArrayList<E> {
          private static final long serialVersionUID = 1L;
          public Raw() {}
        }
      }
      """
    ),
    ProcessorHarness.source(
      "q6.LB",
      "package q6; public final class LB { public Hid.L<Object> build() { return new Hid.Impl<>(); } }"
    )
  );

  private static ProcessorHarness.Compilation bridgeHidden(final JavaFileObject... sources) {
    final var all = new ArrayList<>(HIDDEN);
    all.addAll(List.of(sources));
    return bridge(all);
  }

  private static JavaFileObject target(final String fieldType) {
    return ProcessorHarness.source(
      "q6.Dst",
      "package q6; @SuppressWarnings(\"exports\") public record Dst(" + fieldType + " items) {}"
    );
  }

  private static void assertRefusedByName(final ProcessorHarness.Compilation compilation, final String pkg) {
    assertFalse(compilation.success(), "the bridge cannot be emitted");
    assertTrue(
      compilation.hasError("cannot be named from package " + pkg),
      () -> "refused by @Bridge rather than inside the generated file; saw " + compilation.errorMessages()
    );
    assertFalse(
      compilation.hasError("inaccessible"),
      () -> "no error from inside the generated file; saw " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("a container reached through its builder is refused by name where its type cannot be named")
  void aBuilderRouteContainerIsRefusedByName() {
    // The builder's own type is public, so the builder route is open, and the route still writes
    // the container's name: a cast of what build() returns, and the local the bridge reads into.
    final var compilation = bridgeHidden(
      target("Hid.L<String>"),
      ProcessorHarness.source(
        "p6.Src",
        "package p6; @io.github.eschizoid.telescope.annotations.Bridge(q6.Dst.class)" +
          " public record Src(java.util.List<String> items) {}"
      )
    );

    assertRefusedByName(compilation, "p6");
  }

  @Test
  @DisplayName("a field of the same unnameable type on both sides is refused by name from a carrier elsewhere")
  void anUnnameableTypeOnBothSidesIsRefusedByName() {
    // Nothing is converted, so no container plan is made, and the bridge still declares a local of
    // the field's type in the carrier's package.
    final var compilation = bridgeHidden(
      target("Hid.Bag<String>"),
      ProcessorHarness.source(
        "q6.Src",
        "package q6; @SuppressWarnings(\"exports\") public record Src(Hid.Bag<String> items) {}"
      ),
      ProcessorHarness.source(
        "c6.Carrier",
        "package c6; @io.github.eschizoid.telescope.annotations.Bridge(source = q6.Src.class, target = q6.Dst.class)" +
          " public final class Carrier {}"
      )
    );

    assertRefusedByName(compilation, "c6");
  }

  @Test
  @DisplayName("a container inside a field's type is refused by name where its type cannot be named")
  void aNestedUnnameableContainerIsRefusedByName() {
    // The field's own type is a List, which any package can name, and the class converting its
    // elements writes the inner container's type.
    for (final var inner : List.of("Hid.Bag<String>", "Hid.L<String>")) {
      final var compilation = bridgeHidden(
        target("java.util.List<" + inner + ">"),
        ProcessorHarness.source(
          "p6.Src",
          "package p6; @io.github.eschizoid.telescope.annotations.Bridge(q6.Dst.class)" +
            " public record Src(java.util.List<java.util.List<String>> items) {}"
        )
      );

      assertTrue(
        compilation.hasError("container type 'q6.Hid." + inner.substring(4, inner.indexOf('<')) + "'"),
        () -> inner + ": should name the inner container; saw " + compilation.errorMessages()
      );
      assertRefusedByName(compilation, "p6");
    }
  }

  @Test
  @DisplayName("a raw container copied into inside a field's type is refused by name where it cannot be named")
  void aNestedRawCopyIntoAnUnnameableContainerIsRefusedByName() {
    // Two generic classes used raw are copied element for element rather than lifted through a
    // view, and the class doing the copy writes the inner containers' types all the same.
    final var compilation = bridgeHidden(
      target("java.util.List<Hid.Raw>"),
      ProcessorHarness.source(
        "p6.Src",
        "package p6; @io.github.eschizoid.telescope.annotations.Bridge(q6.Dst.class)" +
          " @SuppressWarnings(\"rawtypes\") public record Src(java.util.List<java.util.ArrayList> items) {}"
      )
    );

    assertTrue(
      compilation.hasError("container type 'q6.Hid.Raw'"),
      () -> "should name the inner container; saw " + compilation.errorMessages()
    );
    assertRefusedByName(compilation, "p6");
  }

  @Test
  @DisplayName("a forward-only transform into an unnameable type is bridged, since nothing reads it back")
  void aForwardOnlyTransformIntoAnUnnameableTypeIsBridged() {
    // Forward hands the transform's result straight to the target's constructor without a local,
    // and backward reads no target field for a forward-only row, so the bridge never writes the
    // target type's name.
    final var compilation = bridgeHidden(
      target("Hid.Bag<String>"),
      ProcessorHarness.source(
        "q6.ToBag",
        """
        package q6;
        @SuppressWarnings("exports")
        public final class ToBag
          implements io.github.eschizoid.telescope.conversion.BridgeFn<java.util.List<String>, Hid.Bag<String>> {
          @Override public Hid.Bag<String> forward(final java.util.List<String> in) {
            final var out = new Hid.Bag<String>();
            out.addAll(in);
            return out;
          }
          @Override public java.util.List<String> backward(final Hid.Bag<String> in) { return in; }
        }
        """
      ),
      ProcessorHarness.source(
        "p6.Src",
        """
        package p6;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import io.github.eschizoid.telescope.annotations.Transform;
        @Bridge(value = q6.Dst.class, transforms = {
          @Transform(field = "items", using = q6.ToBag.class, forwardOnly = true)
        })
        public record Src(java.util.List<String> items) {}
        """
      )
    );

    assertTrue(compilation.success(), () -> "should bridge: " + compilation.errorMessages());
  }

  @Test
  @DisplayName("the same container is bridged from the package that declares it")
  void theSameContainerIsBridgedBesideIt() throws IOException {
    final var sources = new ArrayList<>(box());
    sources.add(
      ProcessorHarness.source(
        PACKAGE + ".hiddenbox.BesideSrc",
        """
        package io.github.eschizoid.telescope.codegen.hiddenbox;
        @io.github.eschizoid.telescope.annotations.Bridge(BoxedDst.class)
        public record BesideSrc(java.util.List<String> items) {}
        """
      )
    );
    final var generated = bridge(sources);

    assertTrue(generated.success(), () -> "should bridge: " + generated.errorMessages());
    assertTrue(
      generated
        .generated()
        .get(PACKAGE + ".hiddenbox.BesideSrcBridge")
        .contains("new " + PACKAGE + ".hiddenbox.HiddenBox.Bag<"),
      "allocated by name beside it"
    );
  }
}
