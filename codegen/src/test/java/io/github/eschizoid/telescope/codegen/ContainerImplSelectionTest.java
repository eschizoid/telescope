package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Choosing the class to allocate for a container field has two obligations that are easy to treat
 * as one: the class has to be assignable to the declared type, and it has to be nameable from the
 * helper that allocates it. A choice can satisfy either and fail the other.
 *
 * <p>These compile through the full pipeline. The failures here land in method bodies and field
 * initializers, which {@code -proc:only} does not attribute, so every assertion would hold
 * vacuously under the processing-only harness.
 */
class ContainerImplSelectionTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
  }

  @Test
  @DisplayName("a field typed as an adopter's own interface is reported, not filled with a default impl")
  void adopterInterfaceFieldIsReported() {
    // The shared allocation rules build an interface the table does not name as its family's
    // default, and ArrayList is not one of the declared type.
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.MyListIface",
        "package demo; import java.util.List; public interface MyListIface<T> extends" + " List<T> {}"
      ),
      ProcessorHarness.source(
        "demo.ISrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.IDst.class)
        public record ISrc(demo.MyListIface<String> items) {}
        """
      ),
      ProcessorHarness.source(
        "demo.IDst",
        "package demo; import java.util.List; public record IDst(List<String> items) {}"
      )
    );

    assertFalse(compilation.success(), "telescope cannot construct an adopter's interface");
    assertTrue(
      compilation.hasError("has no instance of its own"),
      () -> "the declared type is the cause and should be named: " + compilation.errorMessages()
    );
    assertFalse(
      compilation.errorMessages().contains("conforms to"),
      () -> "javac's assignability error inside generated code is what this replaces: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("two same-named container subtypes in different packages resolve when elements are bridged")
  void sameNamedSubtypesWithBridgedElements() {
    // The self-contained helper renders every type fully qualified, so the identity-element form of
    // this already works. The element-bridging helpers name their types by simple name plus an
    // import, and two imports of the same simple name cannot coexist.
    final var compilation = compile(
      ProcessorHarness.source(
        "one.Wrap",
        "package one; import java.util.ArrayList; public class Wrap<T> extends ArrayList<T>" + " {}"
      ),
      ProcessorHarness.source(
        "two.Wrap",
        "package two; import java.util.ArrayList; public class Wrap<T> extends ArrayList<T>" + " {}"
      ),
      ProcessorHarness.source("demo.EA", "package demo; public record EA(String v) {}"),
      ProcessorHarness.source("demo.EB", "package demo; public record EB(String v) {}"),
      ProcessorHarness.source(
        "demo.WSrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.WDst.class)
        public record WSrc(one.Wrap<demo.EA> left, two.Wrap<demo.EA> right) {}
        """
      ),
      ProcessorHarness.source(
        "demo.WDst",
        "package demo; public record WDst(one.Wrap<demo.EB> left, two.Wrap<demo.EB> right)" + " {}"
      )
    );

    assertTrue(
      compilation.success(),
      () -> "both subtypes must be nameable from the same bridge: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("each container is allocated through the call the shared allocation rules decide for it")
  void eachContainerIsAllocatedThroughTheDecidedCall() {
    // A container sized from the wrong number is the right class with the right contents, so only
    // the text can tell. Each row is a class whose call differs from the plain no-argument one the
    // helper writes for a class the rules build as itself.
    final var compilation = compile(
      ProcessorHarness.source("demo.EA", "package demo; public record EA(String v) {}"),
      ProcessorHarness.source("demo.EB", "package demo; public record EB(String v) {}"),
      ProcessorHarness.source("demo.Day", "package demo; public enum Day { MON }"),
      ProcessorHarness.source(
        "demo.SSrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import java.util.List;
        import java.util.Map;
        @Bridge(demo.SDst.class)
        public record SSrc(
          List<EA> deque,
          List<EA> vector,
          Map<String, EA> identity,
          Map<String, EA> weak,
          Map<Day, EA> byDay
        ) {}
        """
      ),
      ProcessorHarness.source(
        "demo.SDst",
        """
        package demo;
        public record SDst(
          java.util.Deque<EB> deque,
          java.util.Vector<EB> vector,
          java.util.IdentityHashMap<String, EB> identity,
          java.util.WeakHashMap<String, EB> weak,
          java.util.EnumMap<Day, EB> byDay
        ) {}
        """
      )
    );

    assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
    final var bridge = compilation.generated().get("demo.SSrcBridge");
    for (final var allocation : List.of(
      // An element count, which a list and the two identity-sized maps take as it is.
      "new java.util.ArrayDeque<demo.EB>(src.size())",
      "new java.util.Vector<demo.EB>(src.size())",
      "new java.util.IdentityHashMap<java.lang.String, demo.EB>(src.size())",
      // A table capacity, computed where the JDK ships no factory to compute it.
      "new java.util.WeakHashMap<java.lang.String, demo.EB>((int) Math.ceil(src.size() / 0.75d))",
      // The key class, which is all an EnumMap can be built from.
      "new java.util.EnumMap<demo.Day, demo.EB>(demo.Day.class)"
    )) {
      assertTrue(bridge.contains(allocation), () -> "expected " + allocation + " in\n" + bridge);
    }
  }
}
