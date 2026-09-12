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
    // concreteImplFqn falls through to the family default for anything it cannot instantiate, and
    // ArrayList is not assignable to the declared type.
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
      compilation.hasError("which telescope cannot construct"),
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
}
