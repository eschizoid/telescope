package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A generic container used raw, paired with another used raw or with a container holding {@code
 * Object}, is copied element for element into the class the target allocates. A raw type is raw
 * wherever it is named, so the generated file names raw types in its field reads, its copy helpers
 * and their fills; what this pins is that it raises none of the warnings that brings, so a consumer
 * compiling with every lint enabled and warnings as errors is not failed by code it did not write.
 */
class RawContainerCopyTest {

  private static ProcessorHarness.Compilation compile(final String srcField, final String tgtField) {
    final JavaFileObject[] sources = {
      ProcessorHarness.source("demo.Gen", "package demo; public class Gen<E> extends java.util.ArrayList<E> {}"),
      ProcessorHarness.source(
        "demo.Objects",
        "package demo; public class Objects extends java.util.ArrayList<Object> {}"
      ),
      ProcessorHarness.source(
        "demo.ObjectTree",
        "package demo; public class ObjectTree extends java.util.TreeSet<Object> {\n" +
          "  public ObjectTree() {}\n" +
          "  public ObjectTree(final java.util.Comparator<Object> c) { super(c); }\n}"
      ),
      ProcessorHarness.source(
        "demo.Sized",
        "package demo; public class Sized<E> extends java.util.ArrayList<E> {\n" +
          "  public Sized(final int capacity) { super(capacity); }\n}"
      ),
      ProcessorHarness.source(
        "demo.Src",
        "package demo;\n@SuppressWarnings(\"rawtypes\")\n@io.github.eschizoid.telescope.annotations.Bridge(Tgt.class)\n" +
          "public record Src(" +
          srcField +
          " items) {}\n"
      ),
      ProcessorHarness.source(
        "demo.Tgt",
        "package demo;\n@SuppressWarnings(\"rawtypes\")\npublic record Tgt(" + tgtField + " items) {}\n"
      ),
    };
    return ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), sources);
  }

  /** The warnings javac raises anywhere in the generated bridge. */
  private static List<String> bridgeWarnings(final ProcessorHarness.Compilation compilation) {
    return compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
      .filter(d -> d.getSource() != null && d.getSource().getName().endsWith("SrcBridge.java"))
      .map(d -> d.getLineNumber() + ": " + d.getMessage(null))
      .toList();
  }

  @Test
  @DisplayName("a bridge copying raw uses and Object containers raises no warning anywhere in its file")
  void theRawCopyRaisesNoWarning() {
    for (final var pair : List.of(
      List.of("Gen", "Objects"),
      List.of("Objects", "Gen"),
      List.of("Gen", "java.util.LinkedList"),
      List.of("java.util.HashMap", "java.util.LinkedHashMap"),
      List.of("java.util.TreeSet", "java.util.concurrent.ConcurrentSkipListSet"),
      List.of("java.util.TreeSet", "ObjectTree"),
      List.of("java.util.List", "java.util.ArrayList"),
      List.of("java.util.List<Gen>", "java.util.List<Objects>")
    )) {
      final var compilation = compile(pair.get(0), pair.get(1));
      assertTrue(compilation.success(), () -> pair + " should compile: " + compilation.errorMessages());
      assertEquals(List.of(), bridgeWarnings(compilation), () -> pair + " raised warnings in the bridge");
    }
  }

  @Test
  @DisplayName("a raw use with no public no-argument constructor is refused by name, as a field and as an element")
  void aCopyIntoAClassWithNoConstructorIsRefused() {
    // The compile-time world does not probe a concrete class's constructors, so the shared spec
    // accepts the copy and the generated code's own allocation check has to refuse it.
    for (final var pair : List.of(
      List.of("java.util.ArrayList", "Sized"),
      List.of("java.util.List<java.util.ArrayList>", "java.util.List<Sized>")
    )) {
      final var compilation = compile(pair.get(0), pair.get(1));
      assertTrue(!compilation.success(), () -> pair + " should be refused");
      assertTrue(
        compilation.errorMessages().contains("demo.Sized"),
        () -> pair + " should name the class it cannot build: " + compilation.errorMessages()
      );
    }
  }
}
