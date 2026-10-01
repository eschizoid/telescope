package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A container subtype that fixes its supertype's arguments and declares no type parameters of its
 * own, such as {@code class Names extends ArrayList<Name>}, is allocated by its bare name. javac
 * rejects both a diamond and a type-argument list on a class with no type parameters, so every
 * route that allocates one has to leave them off: the element-bridging helpers, the sorted
 * allocation that hands over a comparator, the inline copy constructor, and a nested element
 * container.
 *
 * <p>Every case compiles through the full pipeline, because the allocation is an expression in a
 * method body, and {@code -proc:only} stops before method bodies.
 */
class NonGenericSubtypeAllocationTest {

  private static final List<JavaFileObject> ELEMENTS = List.of(
    ProcessorHarness.source("demo.Name", "package demo; public record Name(String v) {}"),
    ProcessorHarness.source("demo.NameDto", "package demo; public record NameDto(String v) {}")
  );

  private static Compilation compile(final String srcField, final String tgtField, final JavaFileObject... types) {
    final var sources = new ArrayList<>(ELEMENTS);
    sources.addAll(List.of(types));
    sources.add(
      ProcessorHarness.source(
        "demo.Src",
        "package demo;\nimport io.github.eschizoid.telescope.annotations.Bridge;\n@Bridge(demo.Tgt.class)\n" +
          "public record Src(" +
          srcField +
          " items) {}\n"
      )
    );
    sources.add(ProcessorHarness.source("demo.Tgt", "package demo; public record Tgt(" + tgtField + " items) {}"));
    return ProcessorHarness.compileFully(
      List.of(new BridgeProcessor()),
      List.of(),
      sources.toArray(JavaFileObject[]::new)
    );
  }

  private static void assertAllocatedBare(final Compilation compilation, final String type) {
    assertTrue(compilation.success(), () -> "the generated source must compile: " + compilation.errorMessages());
    final var bridge = compilation.generated().get("demo.SrcBridge");
    assertFalse(
      bridge.contains(type + "<"),
      () -> type + " declares no type parameters, so nothing may follow its name: " + bridge
    );
    assertTrue(bridge.contains("new " + type + "("), () -> "the bridge should allocate " + type + ": " + bridge);
  }

  @Test
  @DisplayName("a non-generic list target is allocated bare by the element-bridging helper")
  void bridgedListTarget() {
    final var names = ProcessorHarness.source(
      "demo.NameDtos",
      "package demo; public class NameDtos extends java.util.ArrayList<NameDto> {}"
    );
    assertAllocatedBare(compile("java.util.List<demo.Name>", "demo.NameDtos", names), "demo.NameDtos");
  }

  @Test
  @DisplayName("a non-generic map target is allocated bare by the element-bridging helper")
  void bridgedMapTarget() {
    final var names = ProcessorHarness.source(
      "demo.NameDtoMap",
      "package demo; public class NameDtoMap extends java.util.LinkedHashMap<String, NameDto> {}"
    );
    assertAllocatedBare(compile("java.util.Map<String, demo.Name>", "demo.NameDtoMap", names), "demo.NameDtoMap");
  }

  @Test
  @DisplayName("a non-generic sorted target taking a comparator is allocated bare on both arms")
  void sortedTargetWithComparator() {
    final var names = ProcessorHarness.source(
      "demo.SortedNameDtos",
      """
      package demo;
      public class SortedNameDtos extends java.util.TreeMap<String, NameDto> {
        public SortedNameDtos() {}
        public SortedNameDtos(final java.util.Comparator<? super String> order) { super(order); }
      }
      """
    );
    final var compilation = compile("java.util.SortedMap<String, demo.Name>", "demo.SortedNameDtos", names);
    assertAllocatedBare(compilation, "demo.SortedNameDtos");
    assertTrue(
      compilation.generated().get("demo.SrcBridge").contains("new demo.SortedNameDtos(__"),
      "the comparator arm hands the ordering over"
    );
  }

  @Test
  @DisplayName("a non-generic target with a copy constructor is copy-constructed bare")
  void inlineCopyTarget() {
    final var names = ProcessorHarness.source(
      "demo.Labels",
      """
      package demo;
      public class Labels extends java.util.ArrayList<String> {
        public Labels() {}
        public Labels(final java.util.Collection<? extends String> from) { super(from); }
      }
      """
    );
    assertAllocatedBare(compile("java.util.List<String>", "demo.Labels", names), "demo.Labels");
  }

  @Test
  @DisplayName("a non-generic container nested as an element is allocated bare")
  void nestedElementContainer() {
    final var names = ProcessorHarness.source(
      "demo.NameDtos",
      "package demo; public class NameDtos extends java.util.ArrayList<NameDto> {}"
    );
    assertAllocatedBare(
      compile("java.util.List<java.util.List<demo.Name>>", "java.util.List<demo.NameDtos>", names),
      "demo.NameDtos"
    );
  }
}
