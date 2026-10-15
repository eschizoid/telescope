package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * javac reports a processor whose supported source version is older than the compilation's source
 * level with a warning that carries no source file, so {@code -Werror} fails a build on it whatever
 * the processor generates. javac only consults a processor once a source carries an annotation the
 * processor claims, so each case compiles a source carrying its processor's trigger.
 *
 * <p>Claiming the compiler's newest level lets a type kind added after the processors were written
 * reach them, so the kinds they do not model have to be refused rather than emitted.
 */
class ProcessorSourceVersionTest {

  private static final List<String> NEWEST_LEVEL = List.of(
    "--release",
    Integer.toString(SourceVersion.latestSupported().runtimeVersion().feature()),
    "-Xlint:all"
  );

  static Stream<Arguments> processors() {
    return Stream.of(
      processor(
        FocusProcessor::new,
        "demo.FocusUser",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Focus;
        @Focus
        public record FocusUser(String name) {}
        """
      ),
      processor(
        BeanFocusProcessor::new,
        "demo.BeanUser",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.BeanFocus;
        @BeanFocus
        public class BeanUser {
          private String name;
          public BeanUser() {}
          public String getName() { return name; }
          public void setName(final String name) { this.name = name; }
        }
        """
      ),
      processor(
        BridgeProcessor::new,
        "demo.BridgeUser",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.Bridge;
        @Bridge(BridgeUserDto.class)
        public record BridgeUser(String name) {}
        record BridgeUserDto(String name) {}
        """
      ),
      processor(
        FromMapProcessor::new,
        "demo.MapUser",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.FromMap;
        @FromMap
        public record MapUser(String name) {}
        """
      ),
      processor(
        TelescopeMapperProcessor::new,
        "demo.UserProjection",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.TelescopeMapper;
        import io.github.eschizoid.telescope.inject.TelescopeProjection;
        record User(String name) {}
        record UserDto(String name) {}
        @TelescopeMapper
        interface UserProjection extends TelescopeProjection<User, UserDto> {}
        """
      ),
      processor(
        MapperVerifierProcessor::new,
        "demo.Plain",
        """
        package demo;
        public record Plain(String name) {}
        """
      )
    );
  }

  private static Arguments processor(final Supplier<Processor> processor, final String fqcn, final String code) {
    final var instance = processor.get();
    return Arguments.of(instance.getClass().getSimpleName(), instance, source(fqcn, code));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("processors")
  @DisplayName("A processor claims the newest source level the compiler supports, so javac does not warn about it")
  void noSourceVersionWarningAtTheNewestLevel(
    final String name,
    final Processor processor,
    final JavaFileObject trigger
  ) {
    final var compilation = ProcessorHarness.compileFully(List.of(processor), NEWEST_LEVEL, trigger);
    final var sourceless = compilation
      .diagnostics()
      .stream()
      .filter(d -> d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
      .filter(d -> d.getSource() == null)
      .map(d -> d.getMessage(Locale.ROOT))
      .toList();
    assertTrue(sourceless.isEmpty(), () -> name + " drew warnings with no source file: " + sourceless);
  }

  /** Asks, in the first round, which kinds of type the metadata holder can name as a constant. */
  private static final class Asking extends AbstractProcessor {

    private final Map<String, Boolean> answers = new LinkedHashMap<>();
    private final Map<String, String> reasons = new LinkedHashMap<>();

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
      final var types = processingEnv.getTypeUtils();
      final var bounded = processingEnv.getElementUtils().getTypeElement("demo.Bounded");
      final var method = ElementFilter.methodsIn(bounded.getEnclosedElements()).getFirst();
      final var variable = (TypeVariable) method.getTypeParameters().getFirst().asType();
      answers.put("int", AbstractTelescopeProcessor.isEmittableAsTypedConstant(types.getPrimitiveType(TypeKind.INT)));
      answers.put("declared", AbstractTelescopeProcessor.isEmittableAsTypedConstant(bounded.asType()));
      answers.put("array", AbstractTelescopeProcessor.isEmittableAsTypedConstant(types.getArrayType(bounded.asType())));
      answers.put("intersection", AbstractTelescopeProcessor.isEmittableAsTypedConstant(variable.getUpperBound()));
      answers.put("void", AbstractTelescopeProcessor.isEmittableAsTypedConstant(types.getNoType(TypeKind.VOID)));
      answers.put("executable", AbstractTelescopeProcessor.isEmittableAsTypedConstant(method.asType()));
      reasons.put("intersection", AbstractTelescopeProcessor.unemittableReason(variable.getUpperBound()));
      reasons.put("void", AbstractTelescopeProcessor.unemittableReason(types.getNoType(TypeKind.VOID)));
      reasons.put("variable", AbstractTelescopeProcessor.unemittableReason(variable));
      reasons.put("array of variable", AbstractTelescopeProcessor.unemittableReason(types.getArrayType(variable)));
      return false;
    }
  }

  @Test
  @DisplayName("A type kind the metadata holder does not model is refused rather than emitted as a constant")
  void unmodelledTypeKindIsNotEmittable() {
    final var asking = new Asking();
    ProcessorHarness.compile(
      asking,
      source(
        "demo.Bounded",
        """
        package demo;
        public class Bounded {
          public <T extends Number & Comparable<T>> void pick(final T value) {}
        }
        """
      )
    );
    final var expected = new LinkedHashMap<String, Boolean>();
    expected.put("int", true);
    expected.put("declared", true);
    expected.put("array", true);
    expected.put("intersection", false);
    expected.put("void", false);
    expected.put("executable", false);
    assertEquals(expected, asking.answers);

    final var generic = " — generics with wildcard or self-referential bounds are not supported";
    final var noForm = ", which has no constant form";
    final var expectedReasons = new LinkedHashMap<String, String>();
    expectedReasons.put("intersection", noForm);
    expectedReasons.put("void", noForm);
    expectedReasons.put("variable", generic);
    expectedReasons.put("array of variable", generic);
    assertEquals(expectedReasons, asking.reasons);
  }
}
