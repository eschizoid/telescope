package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.internal.pairing.PropertySystem.Access;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The facts the compile-time world gives the shared allocation rules, asked of the same kinds of
 * type the reflection world is asked about, so the rules read one answer whichever world runs them.
 */
class MirrorAllocationFactsTest {

  /** Asks its questions in the first round and keeps the answers. */
  private static final class Asking extends AbstractProcessor {

    private final Map<String, Object> answers = new LinkedHashMap<>();

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
      final var props = new MirrorProps(processingEnv.getTypeUtils(), elements);
      final var primitive = processingEnv.getTypeUtils().getPrimitiveType(TypeKind.INT);
      final var list = elements.getTypeElement("java.util.List").asType();
      final var arrayList = elements.getTypeElement("java.util.ArrayList").asType();
      final var hidden = elements.getTypeElement("demo.Hidden").asType();
      final var packaged = elements.getTypeElement("demo.Packaged").asType();
      final var none = elements.getTypeElement("demo.CapacityOnly").asType();
      answers.put("primitive abstract", props.isAbstractType(primitive));
      answers.put("primitive access", props.noArgConstructorAccess(primitive));
      answers.put("primitive package", props.packageName(primitive));
      answers.put("primitive accepts", props.hasPublicConstructorAccepting(primitive, list));
      answers.put("interface access", props.noArgConstructorAccess(list));
      answers.put("unknown implements", props.isImplementedBy(list, "no.such.Container"));
      answers.put("unknown named", props.typeNamed("no.such.Container"));
      answers.put("nested named", String.valueOf(props.typeNamed("java.util.AbstractMap$SimpleEntry")));
      answers.put("ArrayList implements List", props.isImplementedBy(list, "java.util.ArrayList"));
      answers.put("ArrayList accepts List", props.hasPublicConstructorAccepting(arrayList, list));
      answers.put("private access", props.noArgConstructorAccess(hidden));
      answers.put("package access", props.noArgConstructorAccess(packaged));
      answers.put("no no-arg access", props.noArgConstructorAccess(none));
      answers.put("package name", props.packageName(packaged));
      return false;
    }
  }

  @Test
  @DisplayName("a type that names no class has no class facts, and a class has the ones its source declares")
  void theFactsMatchTheReflectionWorld() {
    final var asking = new Asking();
    ProcessorHarness.compile(
      asking,
      ProcessorHarness.source(
        "demo.Hidden",
        "package demo; public class Hidden extends java.util.ArrayList<String> { private Hidden() {} }"
      ),
      ProcessorHarness.source(
        "demo.Packaged",
        "package demo; public class Packaged extends java.util.ArrayList<String> { protected Packaged() {} }"
      ),
      ProcessorHarness.source(
        "demo.CapacityOnly",
        "package demo; public class CapacityOnly extends java.util.ArrayList<String> {" +
          " public CapacityOnly(int c) { super(c); } }"
      )
    );

    final var expected = new LinkedHashMap<String, Object>();
    expected.put("primitive abstract", false);
    expected.put("primitive access", Access.NONE);
    expected.put("primitive package", "");
    expected.put("primitive accepts", false);
    expected.put("interface access", Access.NONE);
    expected.put("unknown implements", false);
    expected.put("unknown named", null);
    expected.put("nested named", "java.util.AbstractMap.SimpleEntry<K,V>");
    expected.put("ArrayList implements List", true);
    expected.put("ArrayList accepts List", true);
    expected.put("private access", Access.PRIVATE);
    expected.put("package access", Access.PACKAGE);
    expected.put("no no-arg access", Access.NONE);
    expected.put("package name", "demo");
    assertEquals(expected, asking.answers);
  }
}
