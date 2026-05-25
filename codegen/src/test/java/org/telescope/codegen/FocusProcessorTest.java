package org.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests that drive {@link FocusProcessor} in isolation through the JDK's in-memory compilation
 * API ({@link ToolProvider#getSystemJavaCompiler()}). Each test compiles a single record source
 * string with the processor wired in, then asserts on either the captured generated source or the
 * compiler diagnostics. No third-party compile-testing dependency is used.
 */
class FocusProcessorTest {

  /**
   * Compiles {@code sources} with {@link FocusProcessor} attached, capturing generated {@code
   * SOURCE} outputs and all diagnostics.
   */
  private static Compilation compile(final JavaFileObject... sources) {
    final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "no system Java compiler available (need a JDK, not a JRE)");

    final var diagnostics = new DiagnosticCollector<JavaFileObject>();
    final var capturing = new CapturingFileManager(
      compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)
    );

    final JavaCompiler.CompilationTask task = compiler.getTask(
      null,
      capturing,
      diagnostics,
      // -proc:only runs annotation processing without emitting .class files (so nothing leaks
      // into
      // the working tree and there is no read-back compile round). The -Xlint flags mirror
      // the build.
      List.of("-proc:only", "-Xlint:all,-processing"),
      null,
      List.of(sources)
    );
    task.setProcessors(List.of(new FocusProcessor()));

    final boolean success = task.call();
    return new Compilation(success, diagnostics.getDiagnostics(), capturing.generatedSources());
  }

  private static JavaFileObject source(final String fqcn, final String code) {
    return new StringSource(fqcn, code);
  }

  @Nested
  @DisplayName("Happy path — top-level record")
  class HappyPath {

    @Test
    @DisplayName("generates a <Record>Focus class with one lens constant per component")
    void generatesFocusClass() {
      final var compilation = compile(
        source(
          "demo.Person",
          """
          package demo;
          import org.telescope.annotations.Focus;
          @Focus
          public record Person(String name, demo.Address address) {}
          """
        ),
        source(
          "demo.Address",
          """
          package demo;
          public record Address(String city) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      assertTrue(compilation.errors().isEmpty(), () -> "unexpected errors: " + compilation.errorMessages());

      final var generated = compilation.generated().get("demo.PersonFocus");
      assertNotNull(generated, () -> "PersonFocus not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("public final class PersonFocus"), generated);
      assertTrue(generated.contains("import org.telescope.Telescope;"), generated);
      // One typed lens constant per record component, with the field name preserved. The processor
      // emits TypeMirror.toString() for the field type, which is the fully-qualified name.
      assertTrue(generated.contains("public static final Telescope<Person, java.lang.String> name ="), generated);
      assertTrue(generated.contains("public static final Telescope<Person, demo.Address> address ="), generated);
      assertTrue(generated.contains("Telescope.lens(Person::name,"), generated);
      assertTrue(generated.contains("Telescope.lens(Person::address,"), generated);
    }

    @Test
    @DisplayName("the canonical-constructor setter rebuilds every component, swapping only the focused one")
    void setterRebuildsAllComponents() {
      final var compilation = compile(
        source(
          "demo.Pair",
          """
          package demo;
          import org.telescope.annotations.Focus;
          @Focus
          public record Pair(String left, String right) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.PairFocus");
      assertNotNull(generated, () -> "PairFocus not generated; saw " + compilation.generated().keySet());

      // For the 'left' lens: new Pair(v, s.right())
      assertTrue(generated.contains("new Pair(v, s.right())"), generated);
      // For the 'right' lens: new Pair(s.left(), v)
      assertTrue(generated.contains("new Pair(s.left(), v)"), generated);
    }
  }

  @Nested
  @DisplayName("Rejections — guards raise compile errors")
  class Rejections {

    @Test
    @DisplayName("@Focus on a non-record type is an error")
    void nonRecordIsRejected() {
      final var compilation = compile(
        source(
          "demo.NotARecord",
          """
          package demo;
          import org.telescope.annotations.Focus;
          @Focus
          public class NotARecord {}
          """
        )
      );

      assertFalse(compilation.success(), "compilation should have failed for a non-record @Focus");
      assertTrue(
        compilation.hasError("@Focus is only supported on records"),
        () -> "expected non-record diagnostic; saw " + compilation.errorMessages()
      );
      assertFalse(
        compilation.generated().containsKey("demo.NotARecordFocus"),
        "no Focus class should be generated for a rejected type"
      );
    }

    @Test
    @DisplayName("@Focus on a nested (non-top-level) record is an error")
    void nestedRecordIsRejected() {
      final var compilation = compile(
        source(
          "demo.Outer",
          """
          package demo;
          import org.telescope.annotations.Focus;
          public class Outer {
            @Focus
            public record Inner(String value) {}
          }
          """
        )
      );

      assertFalse(compilation.success(), "compilation should have failed for a nested @Focus record");
      assertTrue(
        compilation.hasError("@Focus is only supported on top-level records"),
        () -> "expected nested-record diagnostic; saw " + compilation.errorMessages()
      );
      assertFalse(
        compilation.generated().containsKey("demo.InnerFocus"),
        "no Focus class should be generated for a rejected nested record"
      );
    }
  }

  @Nested
  @DisplayName("Primitive boxing — boxedType()")
  class PrimitiveBoxing {

    @Test
    @DisplayName("int component surfaces as Telescope<..., Integer>")
    void intIsBoxedToInteger() {
      final var compilation = compile(
        source(
          "demo.Age",
          """
          package demo;
          import org.telescope.annotations.Focus;
          @Focus
          public record Age(int age) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.AgeFocus");
      assertNotNull(generated, () -> "AgeFocus not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("public static final Telescope<Age, Integer> age ="), generated);
      // The primitive name must not leak into the reference-typed Telescope parameter.
      assertFalse(generated.contains("Telescope<Age, int>"), generated);
    }

    @Test
    @DisplayName("every primitive component is mapped to its wrapper type")
    void allPrimitivesAreBoxed() {
      final var compilation = compile(
        source(
          "demo.Primitives",
          """
          package demo;
          import org.telescope.annotations.Focus;
          @Focus
          public record Primitives(
              boolean b, byte by, short sh, int i, long l, char c, float f, double d) {}
          """
        )
      );

      assertTrue(compilation.success(), () -> "compilation failed: " + compilation.errorMessages());
      final var generated = compilation.generated().get("demo.PrimitivesFocus");
      assertNotNull(generated, () -> "PrimitivesFocus not generated; saw " + compilation.generated().keySet());

      assertTrue(generated.contains("Telescope<Primitives, Boolean> b ="), generated);
      assertTrue(generated.contains("Telescope<Primitives, Byte> by ="), generated);
      assertTrue(generated.contains("Telescope<Primitives, Short> sh ="), generated);
      assertTrue(generated.contains("Telescope<Primitives, Integer> i ="), generated);
      assertTrue(generated.contains("Telescope<Primitives, Long> l ="), generated);
      assertTrue(generated.contains("Telescope<Primitives, Character> c ="), generated);
      assertTrue(generated.contains("Telescope<Primitives, Float> f ="), generated);
      assertTrue(generated.contains("Telescope<Primitives, Double> d ="), generated);
    }
  }

  /**
   * Outcome of one in-memory compilation: success flag, diagnostics, and captured generated source.
   */
  private record Compilation(
    boolean success,
    List<Diagnostic<? extends JavaFileObject>> diagnostics,
    Map<String, String> generated
  ) {
    List<Diagnostic<? extends JavaFileObject>> errors() {
      final var out = new ArrayList<Diagnostic<? extends JavaFileObject>>();
      for (final var d : diagnostics) {
        if (d.getKind() == Diagnostic.Kind.ERROR) {
          out.add(d);
        }
      }
      return out;
    }

    boolean hasError(final String fragment) {
      for (final var d : errors()) {
        if (d.getMessage(Locale.ROOT).contains(fragment)) {
          return true;
        }
      }
      return false;
    }

    String errorMessages() {
      final var sb = new StringBuilder();
      for (final var d : diagnostics) {
        sb.append(d.getKind()).append(": ").append(d.getMessage(Locale.ROOT)).append('\n');
      }
      return sb.toString();
    }
  }

  /** A source {@link JavaFileObject} backed by an in-memory string. */
  private static final class StringSource extends SimpleJavaFileObject {

    private final String code;

    StringSource(final String fqcn, final String code) {
      super(URI.create("string:///" + fqcn.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
      this.code = code;
    }

    @Override
    public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
      return code;
    }
  }

  /**
   * Wraps the standard file manager and captures any {@code SOURCE}-kind outputs the processor
   * emits via the {@code Filer}, keyed by the binary name the compiler requested.
   */
  private static final class CapturingFileManager extends ForwardingJavaFileManager<JavaFileManager> {

    private final Map<String, CapturedSource> captured = new LinkedHashMap<>();

    CapturingFileManager(final JavaFileManager delegate) {
      super(delegate);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(
      final Location location,
      final String className,
      final JavaFileObject.Kind kind,
      final javax.tools.FileObject sibling
    ) throws IOException {
      if (location == StandardLocation.SOURCE_OUTPUT && kind == JavaFileObject.Kind.SOURCE) {
        final var sourceFile = new CapturedSource(className);
        captured.put(className, sourceFile);
        return sourceFile;
      }
      return super.getJavaFileForOutput(location, className, kind, sibling);
    }

    Map<String, String> generatedSources() {
      final var out = new LinkedHashMap<String, String>();
      captured.forEach((name, file) -> out.put(name, file.text()));
      return out;
    }
  }

  /** In-memory sink for a single generated source file. */
  private static final class CapturedSource extends SimpleJavaFileObject {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    CapturedSource(final String className) {
      super(URI.create("mem:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
    }

    @Override
    public OutputStream openOutputStream() {
      return bytes;
    }

    // The compiler reads generated sources back in the next round; serve the captured bytes.
    @Override
    public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
      return text();
    }

    String text() {
      return bytes.toString(StandardCharsets.UTF_8);
    }
  }
}
