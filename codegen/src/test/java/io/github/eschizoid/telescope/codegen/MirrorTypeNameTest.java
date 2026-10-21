package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.compileFully;
import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandles;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The compile-time world names a type in a diagnostic as the reflection world does, {@code
 * Type#getTypeName}, so a refusal both paths raise reads the same on both.
 */
class MirrorTypeNameTest {

  private static final String PACKAGE = "io.github.eschizoid.telescope.codegen";

  /** A field of each kind of type a refusal can name. */
  private static final String SHAPES = """
    package io.github.eschizoid.telescope.codegen;
    import java.lang.annotation.ElementType;
    import java.lang.annotation.Target;
    import java.util.List;
    import java.util.Map;
    @Target(ElementType.TYPE_USE) @interface TnMark {}
    class TnShapes<T> {
      class Inner<U> {}
      class Mid { class Deeper {} }
      static class Nested<U> {}
      int primitive;
      int[] ints;
      String[][] strings;
      T variable;
      T[] variables;
      Map<String, List<? extends Number>> map;
      List<? super Integer> lower;
      List<?> any;
      List<? extends Object> anyObject;
      Nested<String> nested;
      Nested<String>[] nestedArray;
      TnShapes<String>.Inner<Integer> inner;
      Map.Entry<String, T> entry;
      List raw;
      TnPlain.Deep deep;
      TnShapes<String>.Mid.Deeper deeper;
      @TnMark int markedInt;
      @TnMark int[] markedInts;
      Map<String, @TnMark int[]> markedMap;
    }
    class TnPlain {
      class Deep {}
    }
    """;

  /** Names the type of each field of {@code TnShapes} in the first round and keeps the names. */
  private static final class Naming extends AbstractProcessor {

    private final Map<String, String> names = new LinkedHashMap<>();

    @Override
    public Set<String> getSupportedAnnotationTypes() {
      return Set.of("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment round) {
      if (!names.isEmpty()) return false;
      final var props = new MirrorProps(processingEnv.getTypeUtils(), processingEnv.getElementUtils());
      final var shapes = processingEnv.getElementUtils().getTypeElement(PACKAGE + ".TnShapes");
      for (final var field : ElementFilter.fieldsIn(shapes.getEnclosedElements())) {
        names.put(field.getSimpleName().toString(), props.typeName(field.asType()));
      }
      return false;
    }
  }

  @Test
  @DisplayName("every field's type is named as its reflected type names itself")
  @SuppressWarnings("rawtypes")
  void namesMatchTheReflectedTypeName() {
    final var naming = new Naming();
    final var compilation = compileFully(List.of(naming), List.of(), source(PACKAGE + ".TnShapes", SHAPES));
    assertTrue(compilation.success(), compilation::errorMessages);
    final var shapes = compilation.define(MethodHandles.lookup()).get(PACKAGE + ".TnShapes");
    final var reflected = new LinkedHashMap<String, String>();
    for (final var field : shapes.getDeclaredFields()) {
      if (field.isSynthetic()) continue;
      reflected.put(field.getName(), field.getGenericType().getTypeName());
    }
    assertEquals(
      List.of(
        "primitive",
        "ints",
        "strings",
        "variable",
        "variables",
        "map",
        "lower",
        "any",
        "anyObject",
        "nested",
        "nestedArray",
        "inner",
        "entry",
        "raw",
        "deep",
        "deeper",
        "markedInt",
        "markedInts",
        "markedMap"
      ),
      List.copyOf(naming.names.keySet())
    );
    assertEquals(reflected, naming.names);
  }
}
