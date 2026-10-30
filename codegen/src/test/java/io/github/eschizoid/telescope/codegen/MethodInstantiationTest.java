package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The instantiation of a generic method's variables its result's shape fixes, against what javac
 * itself decides for the same call: a result refused fits under no instantiation, so javac rejects
 * the call too; a result fixed to a type the taken type takes is one javac accepts the call for;
 * and a call left to javac is one javac accepts. A variable a final class bounds is the one place a
 * refusal and javac part: javac accepts an intersection of the class and an interface it does not
 * implement, which holds no value but null. A call left to javac that javac rejects fails the test.
 */
class MethodInstantiationTest {

  /** How an answer stands against javac's verdict on the same call. */
  private enum Mark {
    /** The answer agrees with javac. */
    AGREES,
    /** Refused where javac accepts, for a variable a final class bounds. */
    STRICTER,
  }

  /**
   * One call: the generic method as declared, the argument's type where it takes one, the type its
   * result is passed as, what the instantiation answers ({@code refused}, {@code open} where javac
   * is left to infer it, or the result's type), and how that answer stands against javac's.
   */
  private record Call(String method, String argument, String taken, String owed, Mark mark) {
    Call(final String method, final String argument, final String taken, final String owed) {
      this(method, argument, taken, owed, Mark.AGREES);
    }
  }

  private static final String REFUSED = "refused";
  private static final String OPEN = "open";

  private static final List<Call> CALLS = List.of(
    new Call("<T extends String> List<T> m()", null, "List<Comparable<String>>", REFUSED),
    new Call("<T extends Integer> List<T> m()", null, "List<CharSequence>", REFUSED),
    new Call("<T extends List<Integer>> List<T> m()", null, "List<List<String>>", REFUSED),
    new Call("<T extends CharSequence & Comparable<T>> List<T> m()", null, "List<Integer>", REFUSED),
    new Call("<T extends CharSequence> List<T> m()", null, "List<String>", "java.util.List<java.lang.String>"),
    new Call("<T extends Comparable<T>> List<T> m()", null, "List<String>", "java.util.List<java.lang.String>"),
    new Call("<T> T m(T t)", "ArrayList<String>", "List<String>", "java.util.ArrayList<java.lang.String>"),
    new Call("<T> List<T> m(T t)", "String", "List<String>", "java.util.List<java.lang.String>"),
    new Call("<T> List<T> m(T t)", "String", "List<CharSequence>", "java.util.List<java.lang.CharSequence>"),
    new Call("<T> List<T> m(T t)", "Integer", "List<String>", REFUSED),
    new Call("<T> List<T> m(T t)", "int", "List<Integer>", "java.util.List<java.lang.Integer>"),
    new Call("<T extends Integer> T m()", null, "Number", "java.lang.Integer"),
    new Call("<T extends Integer> T m()", null, "List<String>", "java.lang.Integer", Mark.STRICTER),
    new Call("<T extends Integer> List<T> m()", null, "List<? extends CharSequence>", REFUSED, Mark.STRICTER),
    new Call("<T extends Number> T m()", null, "String", REFUSED),
    new Call("<T extends Number> T m()", null, "CharSequence", OPEN),
    new Call("<T extends Comparable<Integer>> T m()", null, "Comparable<String>", REFUSED),
    new Call("<T extends ArrayList<Integer>> T m()", null, "List<String>", REFUSED),
    new Call("<T extends List<Integer>> T m()", null, "ArrayList<String>", REFUSED),
    new Call("<T extends Number> List<T> m()", null, "List<? super String>", REFUSED),
    new Call("<T> List<T> m()", null, "List<? super Integer>", OPEN),
    new Call("<T extends Number> List<T> m()", null, "List<? extends Integer>", "java.util.List<java.lang.Integer>"),
    new Call("<T> List<T> m(T t)", "String", "List<? extends CharSequence>", "java.util.List<java.lang.String>"),
    new Call("<T> List<T> m(T t)", "String", "List<? extends Integer>", REFUSED),
    new Call("<T> Collection<T> m()", null, "List<String>", REFUSED),
    new Call("<T> Collection<T> m(T t)", "String", "List<String>", "java.util.Collection<java.lang.String>"),
    new Call("<T> List<T> m()", null, "int", REFUSED),
    new Call("<T> T[] m()", null, "String[]", "java.lang.String[]"),
    new Call("<T extends Number> T[] m()", null, "String[]", REFUSED),
    new Call("<T> T[] m()", null, "List<String>", REFUSED),
    new Call("<T> T[] m()", null, "Object", OPEN),
    new Call("<T> List<? extends T> m()", null, "List<String>", REFUSED),
    new Call("<T> List<? extends T> m()", null, "List<? extends String>", "java.util.List<? extends java.lang.String>"),
    new Call("<T> List<? super T> m()", null, "List<? super String>", OPEN),
    new Call("<T extends Number> List<? super T> m()", null, "List<? super String>", REFUSED),
    new Call("<T> List<? extends T> m()", null, "List<? super String>", REFUSED),
    new Call("<T> List<T> m()", null, "List", OPEN),
    new Call("<T> Map<T, T> m()", null, "Map<String, Integer>", REFUSED),
    new Call("<T> List<List<T>> m()", null, "List<List<String>>", "java.util.List<java.util.List<java.lang.String>>"),
    new Call("<T> List<List<T>> m()", null, "List<Set<String>>", REFUSED),
    new Call("<T> List<List<T>> m()", null, "List<String[]>", REFUSED),
    new Call("<T> List<T[]> m()", null, "List<String[]>", "java.util.List<java.lang.String[]>"),
    new Call("<T> List<T[]> m()", null, "List<String>", REFUSED),
    new Call("<T> List<T[]> m()", null, "List<? super String[]>", OPEN),
    new Call(
      "<T> List<List<T>> m()",
      null,
      "List<? super List<String>>",
      "java.util.List<java.util.List<java.lang.String>>"
    ),
    new Call("<T extends Number> List<List<T>> m()", null, "List<? super List<String>>", REFUSED),
    new Call(
      "<T> List<Collection<T>> m()",
      null,
      "List<? super ArrayList<String>>",
      "java.util.List<java.util.Collection<java.lang.String>>"
    ),
    new Call("<T> List<List<T>> m()", null, "List<? super List<?>>", REFUSED),
    new Call("<T> List<List<T>> m()", null, "List<? super Set<String>>", REFUSED),
    new Call("<T> List<T[]> m()", null, "List<? super Object>", REFUSED),
    new Call("<T extends Number> List<T[]> m()", null, "List<? super String[]>", REFUSED),
    new Call("<T> List<Comparable<T>> m()", null, "List<? super String[]>", REFUSED),
    new Call("<T> List<Object> m()", null, "List<? super Object>", "java.util.List<java.lang.Object>"),
    new Call("<T> ArrayList<T> m()", null, "List<String>", "java.util.ArrayList<java.lang.String>"),
    new Call("<K, V extends Number> Map<K, V> m()", null, "Map<String, String>", REFUSED),
    new Call(
      "<K, V extends List<K>> Map<K, V> m()",
      null,
      "Map<String, List<String>>",
      "java.util.Map<java.lang.String,java.util.List<java.lang.String>>"
    ),
    new Call("<K, V extends List<K>> Map<K, V> m()", null, "Map<String, List<Integer>>", REFUSED),
    new Call("<K, V> Map<K, V> m()", null, "Map<String, ? extends Number>", OPEN),
    new Call("<T, U extends List<T>> U m(T t)", "String", "List<CharSequence>", OPEN),
    new Call("<T, U extends List<T>> U m(T t)", "String", "Collection<CharSequence>", OPEN),
    new Call("<T, U extends List<T>> U m(T t)", "String", "ArrayList<String>", OPEN),
    new Call("<T, U extends List<T>> U m(T t)", "String", "List<Integer>", REFUSED),
    new Call("<T, U extends T> U m(T t)", "String", "Integer", "java.lang.Integer"),
    new Call("<T, U extends Collection<T>> U m(T t)", "String", "List<String>", OPEN),
    new Call(
      "<T extends Comparable<T>> T m(T t)",
      "java.time.LocalDate",
      "java.time.chrono.ChronoLocalDate",
      "java.time.chrono.ChronoLocalDate"
    ),
    new Call("<T extends Comparable<T>> T m(T t)", "java.sql.Timestamp", "java.util.Date", "java.util.Date"),
    new Call("<T extends Comparable<? super T>> T m(T t)", "java.sql.Timestamp", "java.util.Date", OPEN),
    new Call("<T extends Comparable<T>> T m(T t)", "String", "CharSequence", "java.lang.String"),
    new Call("<T extends Number, U extends Comparable<? extends T>> U m()", null, "String", REFUSED),
    new Call("<T extends Number, U extends Comparable<? super T>> U m()", null, "String", REFUSED),
    new Call("<T, U extends Comparable<? extends T>> U m()", null, "String", "java.lang.String"),
    new Call("<T, U extends Comparable<? extends T>> U m()", null, "java.util.Date", OPEN),
    new Call("<T, U extends Comparable<? super T>> U m()", null, "String", REFUSED),
    new Call("<T, U extends List<? extends T>> U m()", null, "List<String>", OPEN),
    new Call("<T, U extends List<? super T>> U m()", null, "List<String>", REFUSED),
    new Call("<T extends Number, U extends List<? extends T>> U m()", null, "List<String>", REFUSED),
    new Call("<T, U extends List<T>> U m()", null, "List<? extends CharSequence>", REFUSED),
    new Call("<T, U extends List<T>> U m(T t)", "String", "List<? extends CharSequence>", OPEN),
    new Call("<T, U extends List<T>> U m(T t)", "Object", "List<? extends CharSequence>", REFUSED),
    new Call("<T extends Comparable<T>> List<T> m(T t)", "Integer", "List<Number>", REFUSED),
    new Call("<E extends Enum<E>> E m()", null, "Comparable<String>", REFUSED),
    new Call("<E extends Enum<E>> E m()", null, "Comparable<java.time.DayOfWeek>", "java.time.DayOfWeek"),
    new Call("<T extends Number> T m()", null, "String[]", REFUSED),
    new Call("<T extends CharSequence> T m()", null, "Integer", REFUSED, Mark.STRICTER),
    new Call("<T extends Number & Comparable<T>> T m()", null, "Integer", "java.lang.Integer"),
    new Call("<T extends java.nio.file.Path> T m()", null, "Collection<String>", REFUSED),
    new Call("<T extends M.IA> T m()", null, "M.IB", REFUSED),
    new Call("<T extends List<String>> T m()", null, "Set<Integer>", REFUSED),
    new Call("<T extends List<String>> T m()", null, "Set<String>", OPEN),
    new Call("<T extends String> List<? extends T> m()", null, "List<? extends Integer>", REFUSED),
    new Call("<T extends Number> List<? extends T> m()", null, "List<? extends String>", REFUSED),
    new Call("<T extends Number> M.Outer<T>.Inner m()", null, "M.Outer<String>.Inner", REFUSED),
    new Call("<T> M.Outer<T>.Inner m()", null, "M.Outer<String>.Inner", "demo.M.Outer<java.lang.String>.Inner"),
    new Call("<T extends Comparable<T>> Map<T, T> m()", null, "Map<? super Integer, ? super Long>", REFUSED),
    new Call(
      "<T extends Comparable<T>> Map<T, T> m()",
      null,
      "Map<? super java.sql.Date, ? super java.sql.Time>",
      "java.util.Map<java.util.Date,java.util.Date>"
    ),
    new Call("<T extends Comparable<T>> List<T> m()", null, "List<? super Object>", REFUSED),
    new Call("<T> List<? super T> m()", null, "List<? extends Number>", REFUSED),
    new Call("<T extends Number> List<? super T> m()", null, "List<? extends Number>", REFUSED),
    new Call("<T> List<? super T> m()", null, "List<? extends Object>", OPEN),
    new Call("<T extends Number> List<List<? extends T>> m()", null, "List<List<? extends CharSequence>>", REFUSED),
    new Call(
      "<T> List<List<? extends T>> m()",
      null,
      "List<List<? extends CharSequence>>",
      "java.util.List<java.util.List<? extends java.lang.CharSequence>>"
    ),
    new Call("<T extends Comparable<? super T>> Map<T, T> m()", null, "Map<? super Integer, ? super Long>", REFUSED),
    new Call(
      "<T extends Comparable<? super T>> Map<T, T> m()",
      null,
      "Map<? super java.sql.Date, ? super java.sql.Timestamp>",
      OPEN
    ),
    new Call("<T> T m(List<? extends T> t)", "List<String>", "Integer", REFUSED),
    new Call("<T> T m(List<? extends T> t)", "List<String>", "CharSequence", OPEN)
  );

  /** Asks the instantiation about each call in the first round and keeps the answers. */
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
      final var instantiation = new MethodInstantiation(types, props);
      final var methods = elements.getTypeElement("demo.M");
      final var takes = elements.getTypeElement("demo.Takes");
      for (final var method : ElementFilter.methodsIn(methods.getEnclosedElements())) {
        final var name = method.getSimpleName().toString();
        final var take = ElementFilter.methodsIn(takes.getEnclosedElements())
          .stream()
          .filter(m -> m.getSimpleName().contentEquals(name))
          .findFirst()
          .orElseThrow();
        final var parameters = take.getParameters();
        final TypeMirror taken = parameters.getLast().asType();
        final TypeMirror argument = parameters.size() > 1 ? parameters.getFirst().asType() : null;
        final var result = instantiation.result((ExecutableType) method.asType(), argument, taken);
        answers.put(name, result == null ? REFUSED : props.mentionsTypeVariable(result) ? OPEN : result.toString());
        answers.put(name + " takes", String.valueOf(result != null && types.isAssignable(result, taken)));
      }
      return false;
    }
  }

  private static final String IMPORTS = "import java.util.*;\n";

  /** The class declaring each call's method, as {@code m<index>}. */
  private static JavaFileObject methods() {
    final var body = new StringBuilder(
      "package demo;\n" +
        IMPORTS +
        "public class M {\n" +
        "  public interface IA extends Comparable<String> {}\n" +
        "  public interface IB extends Comparable<Integer> {}\n" +
        "  public static class Outer<T> { public class Inner {} }\n"
    );
    for (int i = 0; i < CALLS.size(); i++) {
      final var call = CALLS.get(i);
      body
        .append("  public static ")
        .append(call.method().replace(" m(", " m" + i + "("))
        .append(" { return null; }\n");
    }
    return ProcessorHarness.source("demo.M", body.append("}\n").toString());
  }

  /** The class whose {@code m<index>} takes each call's argument, if any, and its taken type. */
  private static JavaFileObject takes() {
    final var body = new StringBuilder("package demo;\n" + IMPORTS + "public class Takes {\n");
    for (int i = 0; i < CALLS.size(); i++) {
      final var call = CALLS.get(i);
      body.append("  public static void m").append(i).append("(");
      if (call.argument() != null) body.append(call.argument()).append(" a, ");
      body.append(call.taken()).append(" t) {}\n");
    }
    return ProcessorHarness.source("demo.Takes", body.append("}\n").toString());
  }

  /** Each call made as javac would compile it, one class each, as {@code demo.Call<index>}. */
  private static List<JavaFileObject> calls() {
    final var sources = new ArrayList<JavaFileObject>();
    for (int i = 0; i < CALLS.size(); i++) {
      final var call = CALLS.get(i);
      final var argument = call.argument() == null ? "" : call.argument() + " a";
      final var passed = call.argument() == null ? "" : "a";
      sources.add(
        ProcessorHarness.source(
          "demo.Call" + i,
          "package demo;\n" +
            IMPORTS +
            "public class Call" +
            i +
            " {\n  static void take(final " +
            call.taken() +
            " t) {}\n  static void run(" +
            argument +
            ") { take(M.m" +
            i +
            "(" +
            passed +
            ")); }\n}\n"
        )
      );
    }
    return sources;
  }

  @Test
  @DisplayName(
    "a generic method's result is fixed where its shape forces it, refused only where javac refuses the call, and" +
      " fits only where javac accepts it"
  )
  void theInstantiationAgreesWithJavac() {
    final var asking = new Asking();
    ProcessorHarness.compile(asking, methods(), takes());
    final var all = new ArrayList<JavaFileObject>(List.of(methods()));
    all.addAll(calls());
    final var javac = ProcessorHarness.compileFully(List.of(), List.of(), all.toArray(JavaFileObject[]::new));
    final var rejected = new HashSet<String>();
    for (final var diagnostic : javac.diagnostics()) {
      if (diagnostic.getKind() != Diagnostic.Kind.ERROR || diagnostic.getSource() == null) continue;
      rejected.add(diagnostic.getSource().getName().replaceAll(".*/", "").replace(".java", ""));
    }
    final var failures = new ArrayList<String>();
    for (int i = 0; i < CALLS.size(); i++) {
      final var call = CALLS.get(i);
      final var name = "m" + i;
      final var what = call.method() + " into " + call.taken();
      final var answer = asking.answers.get(name);
      if (!call.owed().equals(answer)) failures.add(what + ": answered " + answer + ", owed " + call.owed());
      final var javacRejects = rejected.contains("Call" + i);
      if (REFUSED.equals(answer) && !javacRejects && call.mark() != Mark.STRICTER) {
        failures.add(what + ": refused, but javac accepts the call");
      }
      if (call.mark() == Mark.STRICTER && javacRejects) {
        failures.add(what + ": marked stricter than javac, which rejects it too");
      }
      if ("true".equals(asking.answers.get(name + " takes")) && javacRejects) {
        failures.add(what + ": fits, but javac rejects the call");
      }
      if (OPEN.equals(answer) && javacRejects) failures.add(what + ": left to javac, which rejects the call");
    }
    assertTrue(rejected.stream().noneMatch(name -> name.equals("M")), "the methods should compile");
    assertTrue(failures.isEmpty(), () -> String.join("\n  ", failures));
  }
}
