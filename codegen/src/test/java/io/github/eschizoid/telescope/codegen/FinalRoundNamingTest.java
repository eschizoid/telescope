package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.codegen.ProcessorHarness.Compilation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What javac does with a class a processor writes only in the final round, when {@code
 * processingOver()} is true. javac parses that file and enters it with every other source before it
 * attributes method bodies and initializers, so code there resolves it, declarations local to a
 * body included. In a source entered before the final round it resolves a single-type import and
 * the signature of a member (a field's type, a method's return type) while the rounds run, and in
 * the final round it reports each one it could not resolve, so neither resolves. An on-demand
 * import of the class's package resolves when the package holds another class. javac also warns
 * that the file was created in the last round, which {@code -Werror} turns into a failed build
 * unless {@code -nowarn} is passed too.
 *
 * <p>Each refusal is pinned to the line javac reports it on, and each is paired with the same
 * sources compiled with {@code Late} written in the first round, so a failure belongs to the final
 * round rather than to the sources.
 */
class FinalRoundNamingTest {

  private static final String LATE = "public final class Late { public static String hello() { return \"late\"; } }";

  /**
   * Writes {@code Late} into {@code pkg}, in the first round or the final one, and {@code early} as
   * {@code pkg.Early} in the first round when it is not null.
   */
  @SupportedAnnotationTypes("*")
  private static final class LateWriter extends AbstractProcessor {

    private final String pkg;

    private final boolean lateInFinalRound;

    private final String early;

    private boolean firstRoundDone;

    LateWriter(final String pkg, final boolean lateInFinalRound, final String early) {
      this.pkg = pkg;
      this.lateInFinalRound = lateInFinalRound;
      this.early = early;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
      if (roundEnv.processingOver()) {
        if (lateInFinalRound) write("Late", LATE);
        return false;
      }
      if (firstRoundDone) return false;
      firstRoundDone = true;
      if (!lateInFinalRound) write("Late", LATE);
      if (early != null) write("Early", early);
      return false;
    }

    private void write(final String simpleName, final String body) {
      try (final var out = processingEnv.getFiler().createSourceFile(pkg + "." + simpleName).openWriter()) {
        out.write("package " + pkg + ";\n" + body);
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private static Compilation compile(
    final boolean lateInFinalRound,
    final String early,
    final List<String> options,
    final JavaFileObject... sources
  ) {
    return ProcessorHarness.compileFully(List.of(new LateWriter("demo", lateInFinalRound, early)), options, sources);
  }

  private static Compilation compile(final boolean lateInFinalRound, final JavaFileObject... sources) {
    return compile(lateInFinalRound, null, List.of(), sources);
  }

  /**
   * Whether javac reported an error in {@code file} on {@code line} whose message holds {@code
   * fragment}.
   */
  private static boolean errorAt(
    final Compilation compilation,
    final String file,
    final long line,
    final String fragment
  ) {
    return compilation
      .errors()
      .stream()
      .anyMatch(
        d ->
          d.getSource() != null &&
          d.getSource().getName().endsWith(file) &&
          d.getLineNumber() == line &&
          d.getMessage(Locale.ROOT).contains(fragment)
      );
  }

  private static final JavaFileObject BODY_CALL = ProcessorHarness.source(
    "demo.Use",
    """
    package demo;
    public class Use {
      public static String call() { return Late.hello(); }
    }
    """
  );

  /** A class of package {@code demo} besides {@code Late}, so the package exists in every round. */
  private static final JavaFileObject ANCHOR = ProcessorHarness.source(
    "demo.Anchor",
    """
    package demo;
    public final class Anchor {}
    """
  );

  @Test
  @DisplayName("a method body calls a class written in the final round, and the call runs")
  void aBodyCallsAFinalRoundClass() throws ReflectiveOperationException {
    final var pkg = FinalRoundNamingTest.class.getPackageName();
    final var compilation = ProcessorHarness.compileFully(
      List.of(new LateWriter(pkg, true, null)),
      List.of(),
      ProcessorHarness.source(
        pkg + ".FinalRoundCaller",
        """
        package %s;
        public class FinalRoundCaller {
          public static String call() { return Late.hello(); }
        }
        """.formatted(pkg)
      )
    );

    assertTrue(compilation.success(), compilation::errorMessages);
    assertTrue(compilation.errorMessages().contains("Late' created in the last round"), compilation::errorMessages);
    final var caller = compilation.define(MethodHandles.lookup()).get(pkg + ".FinalRoundCaller");
    assertEquals("late", caller.getMethod("call").invoke(null));
  }

  @Test
  @DisplayName("declarations local to a method body resolve a class written in the final round")
  void localDeclarationsResolveAFinalRoundClass() {
    final var compilation = compile(
      true,
      ProcessorHarness.source(
        "demo.Use",
        """
        package demo;
        public class Use {
          public static Object call() {
            record Pair(Late left, Late right) {}
            final Late local = new Late();
            final Object anonymous = new Object() { final Late held = local; };
            return new Pair(local, local) + " " + anonymous;
          }
        }
        """
      )
    );

    assertTrue(compilation.success(), compilation::errorMessages);
  }

  @Test
  @DisplayName("a single-type import of a class written in the final round does not resolve")
  void aSingleTypeImportOfAFinalRoundClassFails() {
    final var use = ProcessorHarness.source(
      "other.Use",
      """
      package other;
      import demo.Late;
      public class Use {
        public static String call() { return Late.hello(); }
      }
      """
    );

    assertTrue(compile(false, use).success(), "written in the first round, the import resolves");
    final var late = compile(true, use);
    assertFalse(late.success(), late::errorMessages);
    assertTrue(errorAt(late, "/other/Use.java", 2, "package demo does not exist"), late::errorMessages);
    final var anchored = compile(true, use, ANCHOR);
    assertTrue(errorAt(anchored, "/other/Use.java", 2, "cannot find symbol"), anchored::errorMessages);
  }

  @Test
  @DisplayName("an on-demand import serves a method body when the package holds another class, and no signature")
  void anOnDemandImportServesBodiesOnly() {
    final var body = ProcessorHarness.source(
      "other.Use",
      """
      package other;
      import demo.*;
      public class Use {
        public static String call() { return Late.hello(); }
      }
      """
    );
    final var field = ProcessorHarness.source(
      "other.Use",
      """
      package other;
      import demo.*;
      public class Use {
        static Late late;
      }
      """
    );

    final var bodyCall = compile(true, body, ANCHOR);
    assertTrue(bodyCall.success(), bodyCall::errorMessages);
    final var empty = compile(true, body);
    assertTrue(errorAt(empty, "/other/Use.java", 2, "package demo does not exist"), empty::errorMessages);
    assertTrue(compile(false, field, ANCHOR).success(), "written in the first round, the field's type resolves");
    final var fieldType = compile(true, field, ANCHOR);
    assertTrue(errorAt(fieldType, "/other/Use.java", 4, "cannot find symbol"), fieldType::errorMessages);
  }

  @Test
  @DisplayName("a field typed as a class written in the final round does not resolve")
  void aFieldTypedAsAFinalRoundClassFails() {
    final var use = ProcessorHarness.source(
      "demo.Use",
      """
      package demo;
      public class Use {
        static Late late;
      }
      """
    );

    assertTrue(compile(false, use).success(), "written in the first round, the field's type resolves");
    final var late = compile(true, use);
    assertFalse(late.success(), late::errorMessages);
    assertTrue(errorAt(late, "/demo/Use.java", 3, "cannot find symbol"), late::errorMessages);
  }

  @Test
  @DisplayName("a class written in an earlier round calls a final-round class from a method body")
  void anEarlierClassCallsAFinalRoundClassFromItsBody() {
    final var early = "public final class Early { public static String hop() { return Late.hello(); } }";

    final var compilation = compile(true, early, List.of(), BODY_CALL);

    assertTrue(compilation.success(), compilation::errorMessages);
  }

  @Test
  @DisplayName("a class written in an earlier round cannot return a final-round class")
  void anEarlierClassCannotReturnAFinalRoundClass() {
    final var early = "public final class Early { public static Late late() { return new Late(); } }";

    assertTrue(compile(false, early, List.of(), BODY_CALL).success(), "written in the first round, it resolves");
    final var late = compile(true, early, List.of(), BODY_CALL);
    assertFalse(late.success(), late::errorMessages);
    assertTrue(errorAt(late, "/demo/Early.java", 2, "cannot find symbol"), late::errorMessages);
  }

  @Test
  @DisplayName("the last-round warning fails a -Werror build that otherwise compiles clean, unless -nowarn is passed")
  void theLastRoundWarningFailsWerror() {
    final var werror = List.of("-Werror");

    final var early = compile(false, null, werror, BODY_CALL);
    assertTrue(early.success(), early::errorMessages);
    final var late = compile(true, null, werror, BODY_CALL);
    assertFalse(late.success(), late::errorMessages);
    assertTrue(late.hasError("warnings found and -Werror specified"), late::errorMessages);
    assertTrue(late.errorMessages().contains("demo.Late' created in the last round"), late::errorMessages);
    final var nowarn = compile(true, null, List.of("-Werror", "-nowarn"), BODY_CALL);
    assertTrue(nowarn.success(), nowarn::errorMessages);
  }
}
