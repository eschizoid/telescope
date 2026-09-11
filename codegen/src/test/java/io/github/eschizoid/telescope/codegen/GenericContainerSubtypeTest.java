package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A container subtype that keeps its own type parameter — {@code class MyList<T> extends
 * ArrayList<T> {}} — is an ordinary adopter shape, and the emitter has never been asked to allocate
 * one: every container fixture elsewhere is raw ({@code class Wrap extends ArrayList<Elem> {}}),
 * and the generic shapes exist only in suites that never emit a bridge.
 *
 * <p>Java does not inherit constructors, so such a subtype has its implicit no-arg one and nothing
 * else unless it declares more. Both facts the raw path already respects have to hold here too.
 *
 * <p>Every case compiles through the full pipeline. Under {@code -proc:only} javac never attributes
 * the generated source, so an allocation that cannot compile still reports success and every
 * assertion below would hold vacuously.
 */
class GenericContainerSubtypeTest {

  private static Compilation compile(final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
  }

  private static final JavaFileObject MY_LIST = ProcessorHarness.source(
    "demo.MyList",
    """
    package demo;
    import java.util.ArrayList;
    public class MyList<T> extends ArrayList<T> {}
    """
  );

  @Test
  @DisplayName("a generic subtype holding identity elements is filled, not copy-constructed")
  void genericSubtypeWithIdentityElementsCompiles() {
    final var compilation = compile(
      MY_LIST,
      ProcessorHarness.source(
        "demo.GSrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.GDst.class)
        public record GSrc(demo.MyList<String> items) {}
        """
      ),
      ProcessorHarness.source(
        "demo.GDst",
        "package demo; import java.util.List; public record GDst(List<String> items) {}"
      )
    );

    assertTrue(compilation.success(), () -> "the generated source must compile: " + compilation.errorMessages());
    final var bridge = compilation.generated().get("demo.GSrcBridge");
    assertFalse(
      bridge != null && bridge.contains("new demo.MyList<>("),
      () -> "a subtype declares no copy constructor, so nothing may call one: " + bridge
    );
  }

  @Test
  @DisplayName("a generic subtype whose elements need bridging is allocated with a constructor it has")
  void genericSubtypeWithBridgedElementsCompiles() {
    final var compilation = compile(
      MY_LIST,
      ProcessorHarness.source(
        "demo.HSrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.HDst.class)
        public record HSrc(demo.MyList<demo.HElem> items) {}
        """
      ),
      ProcessorHarness.source(
        "demo.HDst",
        "package demo; import java.util.List; public record HDst(List<demo.HElemDto> items)" + " {}"
      ),
      ProcessorHarness.source("demo.HElem", "package demo; public record HElem(String v) {}"),
      ProcessorHarness.source("demo.HElemDto", "package demo; public record HElemDto(String v) {}")
    );

    assertTrue(compilation.success(), () -> "the generated source must compile: " + compilation.errorMessages());
  }

  @Test
  @DisplayName("a subtype that declares a copy constructor keeps the inline copy that uses it")
  void subtypeDeclaringACopyConstructorStillCopies() {
    // Constructors are not inherited, but they can be declared. A subtype offering the one the
    // inline copy needs has no reason to be routed anywhere else — and no reason to be asked for a
    // no-arg constructor it does not have.
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.CopyList",
        """
        package demo;
        import java.util.ArrayList;
        import java.util.Collection;
        public class CopyList<T> extends ArrayList<T> {
          public CopyList(final Collection<? extends T> c) { super(c); }
        }
        """
      ),
      ProcessorHarness.source(
        "demo.CSrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.CDst.class)
        public record CSrc(demo.CopyList<String> items) {}
        """
      ),
      ProcessorHarness.source(
        "demo.CDst",
        "package demo; import java.util.List; public record CDst(List<String> items) {}"
      )
    );

    assertTrue(compilation.success(), () -> "a declared copy constructor is usable: " + compilation.errorMessages());
    assertFalse(
      compilation.errorMessages().contains("no public no-arg constructor"),
      () -> "it was never asked for a no-arg constructor: " + compilation.errorMessages()
    );
  }

  @Test
  @DisplayName("a JDK container without a copy constructor is filled rather than copy-constructed")
  void jdkContainerLackingACopyConstructorIsFilled() {
    // java.util.Stack has no (Collection) constructor, so the package a class lives in does not
    // decide this; the constructor it declares does.
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.KSrc3",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        import java.util.Stack;
        @Bridge(demo.KDst3.class)
        public record KSrc3(Stack<String> items) {}
        """
      ),
      ProcessorHarness.source(
        "demo.KDst3",
        "package demo; import java.util.List; public record KDst3(List<String> items) {}"
      )
    );

    assertTrue(compilation.success(), () -> "the generated source must compile: " + compilation.errorMessages());
  }

  @Test
  @DisplayName("the Set and Map kinds are repaired too, not only List")
  void genericSubtypeSetAndMapCompile() {
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.MySet",
        "package demo; import java.util.HashSet; public class MySet<T> extends HashSet<T>" + " {}"
      ),
      ProcessorHarness.source(
        "demo.MyMap",
        "package demo; import java.util.HashMap; public class MyMap<K, V> extends" + " HashMap<K, V> {}"
      ),
      ProcessorHarness.source(
        "demo.SMSrc",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.SMDst.class)
        public record SMSrc(demo.MySet<String> tags, demo.MyMap<String, String> byKey) {}
        """
      ),
      ProcessorHarness.source(
        "demo.SMDst",
        """
        package demo;
        import java.util.Map;
        import java.util.Set;
        public record SMDst(Set<String> tags, Map<String, String> byKey) {}
        """
      )
    );

    assertTrue(compilation.success(), () -> "the generated source must compile: " + compilation.errorMessages());
  }

  @Test
  @DisplayName("a generic subtype with no usable constructor is reported, not emitted against")
  void genericSubtypeWithoutNoArgCtorIsReported() {
    final var compilation = compile(
      ProcessorHarness.source(
        "demo.SizedList",
        """
        package demo;
        import java.util.ArrayList;
        // Declares a constructor, so it has no implicit no-arg one — and what the argument means
        // is the author's business, not something the processor can read.
        public class SizedList<T> extends ArrayList<T> {
          public SizedList(final int pageNumber) { super(); }
        }
        """
      ),
      ProcessorHarness.source(
        "demo.ISrc2",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(demo.IDst2.class)
        public record ISrc2(demo.SizedList<demo.HElem2> items) {}
        """
      ),
      ProcessorHarness.source(
        "demo.IDst2",
        "package demo; import java.util.List; public record IDst2(List<demo.HElemDto2>" + " items) {}"
      ),
      ProcessorHarness.source("demo.HElem2", "package demo; public record HElem2(String v) {}"),
      ProcessorHarness.source("demo.HElemDto2", "package demo; public record HElemDto2(String v) {}")
    );

    assertFalse(compilation.success(), "a container the processor cannot allocate must be refused");
    assertTrue(
      compilation.hasError("no public no-arg constructor"),
      () -> "the raw path's diagnostic should cover this shape too: " + compilation.errorMessages()
    );
    assertFalse(
      compilation.errorMessages().contains("cannot be applied to given types"),
      () -> "javac inside generated code is the failure mode being replaced: " + compilation.errorMessages()
    );
  }
}
