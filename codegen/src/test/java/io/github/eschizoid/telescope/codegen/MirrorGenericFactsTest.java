package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The generic-class facts the compile-time world gives the shared pairing rules: the variables a
 * class declares, their bounds, the classes a sealed one permits, the arguments a class gives a
 * supertype, and a type with variables replaced, answered as the reflection world answers them.
 */
class MirrorGenericFactsTest {

  /** Asks its questions in the first round and keeps the answers. */
  private static final class Asking extends AbstractProcessor {

    private final Map<String, String> answers = new LinkedHashMap<>();

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
      if (!answers.isEmpty()) return false;
      final var elements = processingEnv.getElementUtils();
      final var types = processingEnv.getTypeUtils();
      final var props = new MirrorProps(types, elements);
      final var primitive = types.getPrimitiveType(TypeKind.INT);
      final var res = elements.getTypeElement("demo.Res").asType();
      final var ok = elements.getTypeElement("demo.Ok");
      final var ranked = elements.getTypeElement("demo.Ranked");
      final var integer = elements.getTypeElement("java.lang.Integer").asType();
      final var variable = ok.getTypeParameters().getFirst().asType();
      final List<TypeMirror> from = List.of(variable);
      final List<TypeMirror> to = List.of(integer);
      for (final var component : ElementFilter.recordComponentsIn(ok.getEnclosedElements())) {
        answers.put(
          "substitute " + component.getSimpleName(),
          String.valueOf(props.substitute(component.asType(), from, to))
        );
      }
      answers.put("substitute primitive", String.valueOf(props.substitute(primitive, from, to)));
      answers.put(
        "substitute another variable",
        String.valueOf(props.substitute(ranked.getTypeParameters().getFirst().asType(), from, to))
      );
      answers.put("substitute unreplaced", String.valueOf(props.substitute(variable, from, List.of())));
      answers.put("parameters", String.valueOf(props.typeParameters(ok.asType())));
      answers.put("primitive parameters", String.valueOf(props.typeParameters(primitive)));
      answers.put("bounds", String.valueOf(props.upperBounds(variable)));
      answers.put(
        "intersection bounds",
        String.valueOf(props.upperBounds(ranked.getTypeParameters().getFirst().asType()))
      );
      answers.put("class bounds", String.valueOf(props.upperBounds(integer)));
      answers.put("permits", String.valueOf(props.permittedSubclasses(res)));
      answers.put("primitive permits", String.valueOf(props.permittedSubclasses(primitive)));
      answers.put("gives", String.valueOf(props.declaredArgumentsAs(types.erasure(ok.asType()), res)));
      answers.put(
        "fixed gives",
        String.valueOf(props.declaredArgumentsAs(elements.getTypeElement("demo.Count").asType(), res))
      );
      answers.put("unrelated gives", String.valueOf(props.declaredArgumentsAs(integer, res)));
      answers.put(
        "gives a primitive",
        String.valueOf(props.declaredArgumentsAs(elements.getTypeElement("demo.Count").asType(), primitive))
      );
      answers.put("primitive gives", String.valueOf(props.declaredArgumentsAs(primitive, res)));
      answers.put("case", String.valueOf(props.isSealedCase(ok.asType())));
      answers.put("not a case", String.valueOf(props.isSealedCase(ranked.asType())));
      answers.put("primitive case", String.valueOf(props.isSealedCase(primitive)));
      answers.put("is variable", String.valueOf(props.isTypeVariable(variable)));
      answers.put("is not variable", String.valueOf(props.isTypeVariable(integer)));
      return false;
    }
  }

  @Test
  @DisplayName(
    "a generic class's variables, bounds, permits and supertype arguments read as declared, and replace at any depth"
  )
  void theGenericFactsReadAsDeclared() {
    final var asking = new Asking();
    ProcessorHarness.compile(
      asking,
      ProcessorHarness.source("demo.Outer", "package demo; public class Outer<T> { public class Inner {} }"),
      ProcessorHarness.source("demo.Res", "package demo; public sealed interface Res<T> permits Ok, Count {}"),
      ProcessorHarness.source(
        "demo.Ok",
        "package demo; public record Ok<T>(T v, T[] vs, java.util.List<? extends T> up," +
          " java.util.List<? super T> down, java.util.List<?> any, Outer<T>.Inner in, String s) implements Res<T> {}"
      ),
      ProcessorHarness.source("demo.Count", "package demo; public record Count(int n) implements Res<Integer> {}"),
      ProcessorHarness.source(
        "demo.Ranked",
        "package demo; public record Ranked<T extends Number & Comparable<T>>(T v) {}"
      )
    );
    final var expected = new LinkedHashMap<String, String>();
    expected.put("substitute v", "java.lang.Integer");
    expected.put("substitute vs", "java.lang.Integer[]");
    expected.put("substitute up", "java.util.List<? extends java.lang.Integer>");
    expected.put("substitute down", "java.util.List<? super java.lang.Integer>");
    expected.put("substitute any", "java.util.List<?>");
    expected.put("substitute in", "demo.Outer<java.lang.Integer>.Inner");
    expected.put("substitute s", "java.lang.String");
    expected.put("substitute primitive", "int");
    expected.put("substitute another variable", "T");
    expected.put("substitute unreplaced", "T");
    expected.put("parameters", "[T]");
    expected.put("primitive parameters", "[]");
    expected.put("bounds", "[java.lang.Object]");
    expected.put("intersection bounds", "[java.lang.Number, java.lang.Comparable<T>]");
    expected.put("class bounds", "[]");
    expected.put("permits", "[demo.Ok, demo.Count]");
    expected.put("primitive permits", "[]");
    expected.put("gives", "[T]");
    expected.put("fixed gives", "[java.lang.Integer]");
    expected.put("unrelated gives", "[]");
    expected.put("gives a primitive", "[]");
    expected.put("primitive gives", "[]");
    expected.put("case", "true");
    expected.put("not a case", "false");
    expected.put("primitive case", "false");
    expected.put("is variable", "true");
    expected.put("is not variable", "false");
    assertEquals(expected, asking.answers);
  }
}
