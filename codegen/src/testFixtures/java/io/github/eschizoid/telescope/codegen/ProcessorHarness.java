package io.github.eschizoid.telescope.codegen;

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
import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Shared in-memory compilation harness for the annotation-processor tests. Drives one or more
 * processors through the JDK's {@link ToolProvider#getSystemJavaCompiler()} over in-memory source
 * strings, capturing generated {@code SOURCE} outputs and all diagnostics — no third-party
 * compile-testing dependency. Exposed via this module's {@code testFixtures} source set so the
 * downstream {@code :lombok} tests can reuse it alongside the in-tree processor tests.
 */
public final class ProcessorHarness {

  private ProcessorHarness() {}

  /**
   * Compile {@code sources} with the single {@code processor} attached, capturing generated source
   * + diagnostics.
   */
  public static Compilation compile(final Processor processor, final JavaFileObject... sources) {
    return compile(List.of(processor), sources);
  }

  /**
   * Compile {@code sources} with every processor in {@code processors} attached, capturing
   * generated source + diagnostics. List ordering is preserved — javac runs each processor once per
   * round.
   */
  public static Compilation compile(final List<? extends Processor> processors, final JavaFileObject... sources) {
    final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("no system Java compiler available (need a JDK, not a JRE)");
    }

    final var diagnostics = new DiagnosticCollector<JavaFileObject>();
    final var capturing = new CapturingFileManager(
      compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)
    );

    // -proc:only runs annotation processing without emitting .class files (nothing leaks into the
    // working tree, no read-back compile round). The -Xlint flags mirror the build.
    final JavaCompiler.CompilationTask task = compiler.getTask(
      null,
      capturing,
      diagnostics,
      List.of("-proc:only", "-Xlint:all,-processing"),
      null,
      List.of(sources)
    );
    task.setProcessors(List.copyOf(processors));

    final boolean success = task.call();
    return new Compilation(
      success,
      diagnostics.getDiagnostics(),
      capturing.generatedSources(),
      capturing.generatedResources()
    );
  }

  public static JavaFileObject source(final String fqcn, final String code) {
    return new StringSource(fqcn, code);
  }

  /**
   * Outcome of one in-memory compilation: success flag, diagnostics, and captured generated source.
   */
  public record Compilation(
    boolean success,
    List<Diagnostic<? extends JavaFileObject>> diagnostics,
    Map<String, String> generated,
    Map<String, String> resources
  ) {
    public List<Diagnostic<? extends JavaFileObject>> errors() {
      final var out = new ArrayList<Diagnostic<? extends JavaFileObject>>();
      for (final var d : diagnostics) {
        if (d.getKind() == Diagnostic.Kind.ERROR) out.add(d);
      }
      return out;
    }

    public boolean hasError(final String fragment) {
      for (final var d : errors()) {
        if (d.getMessage(Locale.ROOT).contains(fragment)) return true;
      }
      return false;
    }

    public String errorMessages() {
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
    private final Map<String, CapturedResource> capturedResources = new LinkedHashMap<>();

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

    // Capture resource outputs (e.g. META-INF/services registrations) in memory too, so they're
    // assertable and — critically — never escape to disk in the working tree under -proc:only.
    @Override
    public javax.tools.FileObject getFileForOutput(
      final Location location,
      final String packageName,
      final String relativeName,
      final javax.tools.FileObject sibling
    ) {
      final var key = packageName.isEmpty() ? relativeName : packageName.replace('.', '/') + "/" + relativeName;
      final var resource = new CapturedResource(key);
      capturedResources.put(key, resource);
      return resource;
    }

    Map<String, String> generatedSources() {
      final var out = new LinkedHashMap<String, String>();
      captured.forEach((name, file) -> out.put(name, file.text()));
      return out;
    }

    Map<String, String> generatedResources() {
      final var out = new LinkedHashMap<String, String>();
      capturedResources.forEach((name, file) -> out.put(name, file.text()));
      return out;
    }
  }

  /** In-memory sink for a single generated resource (e.g. a {@code META-INF/services} file). */
  private static final class CapturedResource extends SimpleJavaFileObject {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    CapturedResource(final String path) {
      super(URI.create("mem:///" + path), Kind.OTHER);
    }

    @Override
    public OutputStream openOutputStream() {
      return bytes;
    }

    @Override
    public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
      return text();
    }

    String text() {
      return bytes.toString(StandardCharsets.UTF_8);
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
