package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.tools.JavaFileObject;

/**
 * Two holds on a compilation of {@code @Bridge} declarations beyond the one its own gate makes.
 *
 * <p>A compilation declaring its pairs with {@code @Bridge} on their source classes is written
 * again with each pair as a {@code @Bridge} field of a carrier class in its package, which the
 * field form owes the same generated code and the same refusal: a class form names its target and
 * at most {@code lenient}, and a field with no rows, through {@code Telescope.mapperForward} where
 * the class form is lenient, says the same. A compilation whose class-form declarations carry
 * anything else is counted as having no twin.
 *
 * <p>A {@code @Bridge} field with a {@code via} row naming a class written by hand has the runtime
 * path choose that class's methods from its own reading of the class, so the compilation is built
 * again without the processor, and for a source and a target that each give a {@code sample()}, the
 * field's runtime mapper is held to the generated bridge in each direction it converts.
 */
final class FieldTwin {

  private static final Pattern BRIDGE = Pattern.compile(
    "@(?:io\\.github\\.eschizoid\\.telescope\\.annotations\\.)?Bridge\\s*\\("
  );
  private static final Pattern DECLARATION = Pattern.compile(
    "^(?:\\s|@[\\w.]+(?:\\([^)]*\\))?|public|final|abstract|sealed|non-sealed|static|strictfp)*" +
      "(record|class|interface|enum)\\s+(\\w+)"
  );
  private static final String CARRIER = "TwinCarrier";

  /**
   * A {@code @Bridge} field: its mapper type, the pair it names, its name, and the start of its
   * initializer.
   */
  private static final Pattern FIELD = Pattern.compile(
    "@(?:io\\.github\\.eschizoid\\.telescope\\.annotations\\.)?Bridge\\s+" +
      "(?:(?:public|private|protected|static|final)\\s+)*(?:[\\w.]+\\.)?(Mapper|ForwardMapper)\\s*<\\s*([\\w.]+)\\s*,\\s*" +
      "([\\w.]+)\\s*>\\s+(\\w+)\\s*="
  );

  /** A {@code via} row naming a class, as its last argument. */
  private static final Pattern VIA_CLASS = Pattern.compile("\\bvia\\s*\\([^;]*?,\\s*[\\w.]+\\.class\\s*\\)");

  /** The declarations a field may sit in, by name. */
  private static final Pattern TYPE = Pattern.compile("\\b(?:class|record|interface|enum)\\s+(\\w+)");

  /** How many compilations were compiled through the corpus's gate. */
  static final AtomicInteger COMPILED = new AtomicInteger();

  /**
   * How many compilations with a class-form {@code @Bridge} were held to a field twin, and how many
   * had none.
   */
  static final AtomicInteger TWINNED = new AtomicInteger();

  static final AtomicInteger UNTWINNED = new AtomicInteger();

  /** How many pairs naming a class were run on the runtime path against the generated bridge. */
  static final AtomicInteger RUN_BOTH_WAYS = new AtomicInteger();

  /**
   * How many pairs naming a class were not run, for want of a source sample or a generated bridge.
   */
  static final AtomicInteger UNSAMPLED = new AtomicInteger();

  private static final Pattern FIELD_PREFIX = Pattern.compile("^In the rows of @Bridge field \\w+\\.\\w+: ");

  private FieldTwin() {}

  /**
   * Holds {@code sources}, compiled with the processor as {@code original}, to the field twin of
   * its class-form declarations, and the runtime path of each {@code @Bridge} field naming a class
   * in a {@code via} row to the bridge generated for it.
   */
  static void assertAgrees(final ProcessorHarness.Compilation original, final JavaFileObject... sources) {
    COMPILED.incrementAndGet();
    final var namedClasses = fieldsNamingAClass(sources);
    if (!namedClasses.isEmpty() && original.success()) {
      try {
        runtimeAgrees(original, sources, namedClasses);
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    final var names = new LinkedHashMap<String, String>();
    final var twin = twinOf(names, sources);
    if (twin == null) return;
    if (twin.isEmpty()) {
      UNTWINNED.incrementAndGet();
      return;
    }
    final var compiled = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), twin.get());
    TWINNED.incrementAndGet();
    if (!original.success()) {
      assertFalse(
        compiled.success(),
        () -> "the class form is refused and its field twin is not:\n" + original.errorMessages()
      );
      // The field form refuses with the class form's words, once a refusal made on a field's rows
      // is read without its prefix, on either side, since the original may declare fields too. It
      // refuses earlier where a side is nested in another class, which a field's side may not be.
      final var refusal = FIELD_PREFIX.matcher(original.errors().getFirst().getMessage(Locale.ROOT)).replaceFirst("");
      final var twinRefusals = compiled
        .errors()
        .stream()
        .map(d -> FIELD_PREFIX.matcher(d.getMessage(Locale.ROOT)).replaceFirst(""))
        .toList();
      final var earlier = twinRefusals.stream().anyMatch(m -> m.contains("must be a top-level type"));
      assertTrue(
        earlier || twinRefusals.contains(refusal),
        () -> "the field twin is refused otherwise than the class form:\n  " + refusal + "\nvs\n  " + twinRefusals
      );
      return;
    }
    final var expected = new TreeMap<>(original.generated());
    // A field's bridge is named by its pair; the class form names a single-target source's bridge
    // after the source alone, and that is the name each field bridge is compared under.
    final var renamed = new LinkedHashMap<Pattern, String>();
    for (final var e : names.entrySet()) {
      final var classForm = e.getValue();
      if (
        expected
          .keySet()
          .stream()
          .anyMatch(k -> k.equals(classForm) || k.endsWith("." + classForm))
      ) {
        renamed.put(Pattern.compile("\\b" + Pattern.quote(e.getKey()) + "(?!\\w)"), classForm);
      }
    }
    final var actual = new TreeMap<String, String>();
    for (final var e : compiled.generated().entrySet()) {
      final var simple = e.getKey().substring(e.getKey().lastIndexOf('.') + 1);
      if (simple.endsWith("BridgeProvider") && names.containsKey(simple.substring(0, simple.length() - 8))) continue;
      actual.put(untwinned(e.getKey(), renamed), untwinned(e.getValue(), renamed));
    }
    assertEquals(
      expected,
      actual,
      () ->
        "the field twin generates other code than the class form:\n" +
        difference(expected, actual) +
        compiled.errorMessages()
    );
  }

  /**
   * A {@code @Bridge} field naming a class in a via row: its source and target, the class and field
   * declaring it, and whether it converts forward only.
   */
  private record Pair(String source, String target, String carrier, String field, boolean lenient) {}

  /** The {@code @Bridge} fields of {@code sources} with a {@code via} row naming a class. */
  private static List<Pair> fieldsNamingAClass(final JavaFileObject... sources) {
    final var found = new ArrayList<Pair>();
    for (final var source : sources) {
      final var code = text(source);
      final var pkg = packageOf(code);
      final var prefix = pkg.isEmpty() ? "" : pkg + ".";
      final var m = FIELD.matcher(code);
      while (m.find()) {
        final var end = code.indexOf(';', m.end());
        if (end < 0 || !VIA_CLASS.matcher(code.substring(m.end(), end)).find()) continue;
        String carrier = null;
        for (final var type = TYPE.matcher(code.substring(0, m.start())); type.find(); ) carrier = type.group(1);
        if (carrier == null) continue;
        found.add(
          new Pair(
            m.group(2).contains(".") ? m.group(2) : prefix + m.group(2),
            m.group(3).contains(".") ? m.group(3) : prefix + m.group(3),
            prefix + carrier,
            m.group(4),
            m.group(1).equals("ForwardMapper")
          )
        );
      }
    }
    return found;
  }

  /**
   * Holds the runtime path of each pair in {@code pairs} to its generated bridge: {@code sources}
   * compiled without the processor build each field's mapper from its rows, and for a source and a
   * target that each give a {@code sample()}, convert them as the bridge does, or fail as it does.
   */
  @SuppressWarnings("unchecked")
  private static void runtimeAgrees(
    final ProcessorHarness.Compilation original,
    final JavaFileObject[] twin,
    final List<Pair> pairs
  ) throws IOException {
    final var runtime = ProcessorHarness.compileFully(List.of(), List.of(), twin);
    assertTrue(runtime.success(), runtime::errorMessages);
    try (final var generated = loaderOf(original); final var built = loaderOf(runtime)) {
      for (final var pair : pairs) {
        final var sourceSample = sample(generated, pair.source());
        final var bridge = bridgeOf(generated, pair);
        if (sourceSample == null || bridge == null) {
          UNSAMPLED.incrementAndGet();
          continue;
        }
        final var mapper = outcomeOf(() -> built.loadClass(pair.carrier()).getField(pair.field()).get(null));
        if (!(mapper.value() instanceof Mapper<?, ?>) && !(mapper.value() instanceof ForwardMapper<?, ?>)) {
          throw new AssertionError("the runtime path does not build " + pair + ": " + mapper);
        }
        final var expected = outcomeOf(() ->
          bridge.getMethod("forward", sourceSample.getClass()).invoke(null, sourceSample)
        );
        final var builtSample = sample(built, pair.source());
        final var actual = outcomeOf(() ->
          mapper.value() instanceof Mapper<?, ?> m
            ? ((Mapper<Object, Object>) m).forward(builtSample)
            : ((ForwardMapper<Object, Object>) mapper.value()).forward(builtSample)
        );
        assertEquals(expected.text(), actual.text(), () -> pair + " forward");
        RUN_BOTH_WAYS.incrementAndGet();
        final var targetSample = sample(generated, pair.target());
        if (pair.lenient() || targetSample == null || !(mapper.value() instanceof Mapper<?, ?> two)) continue;
        final var back = outcomeOf(() ->
          bridge.getMethod("backward", targetSample.getClass()).invoke(null, targetSample)
        );
        final var builtTarget = sample(built, pair.target());
        final var builtBack = outcomeOf(() -> ((Mapper<Object, Object>) two).backward(builtTarget));
        assertEquals(back.text(), builtBack.text(), () -> pair + " backward");
      }
    }
  }

  /** What a conversion gave, or what it threw. */
  private record Outcome(Object value, String text) {}

  @FunctionalInterface
  private interface Attempt {
    Object get() throws Exception;
  }

  private static Outcome outcomeOf(final Attempt attempt) {
    try {
      final var value = attempt.get();
      return new Outcome(value, describe(value, 0));
    } catch (final Exception | LinkageError e) {
      Throwable cause = e;
      while (
        (cause instanceof InvocationTargetException || cause instanceof ExceptionInInitializerError) &&
        cause.getCause() != null
      ) {
        cause = cause.getCause();
      }
      return new Outcome(null, "threw " + cause.getClass().getSimpleName());
    }
  }

  /**
   * {@code value} by what it holds rather than by its identity: a record's components, a
   * container's class and contents, a class's own {@code toString} where it declares one, and
   * otherwise its class and fields, each described the same way.
   */
  private static String describe(final Object value, final int depth) {
    if (value == null) return "null";
    if (depth > 8) return "...";
    final var type = value.getClass();
    if (type.isRecord()) {
      final var out = new StringBuilder(type.getSimpleName()).append('[');
      for (final var component : type.getRecordComponents()) {
        try {
          component.getAccessor().setAccessible(true);
          out
            .append(component.getName())
            .append('=')
            .append(describe(component.getAccessor().invoke(value), depth + 1));
        } catch (final ReflectiveOperationException e) {
          out.append(component.getName()).append("=?");
        }
        out.append(';');
      }
      return out.append(']').toString();
    }
    if (value instanceof Optional<?> optional) {
      return optional.map(o -> "Optional[" + describe(o, depth + 1) + "]").orElse("Optional.empty");
    }
    if (value instanceof Map<?, ?> map) {
      final var out = new StringBuilder(type.getSimpleName()).append('{');
      for (final var e : map.entrySet()) {
        out.append(describe(e.getKey(), depth + 1)).append('=').append(describe(e.getValue(), depth + 1)).append(';');
      }
      return out.append('}').toString();
    }
    if (value instanceof Iterable<?> iterable) {
      final var out = new StringBuilder(type.getSimpleName()).append('[');
      for (final var element : iterable) out.append(describe(element, depth + 1)).append(';');
      return out.append(']').toString();
    }
    if (type.isArray()) {
      final var out = new StringBuilder(type.getComponentType().getSimpleName()).append("[](");
      for (var i = 0; i < Array.getLength(value); i++) {
        out.append(describe(Array.get(value, i), depth + 1)).append(';');
      }
      return out.append(')').toString();
    }
    if (ownsToString(type)) return type.getSimpleName() + ":" + value;
    final var out = new StringBuilder(type.getSimpleName()).append('{');
    for (Class<?> owner = type; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
      for (final var field : owner.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())) continue;
        try {
          field.setAccessible(true);
          out.append(field.getName()).append('=').append(describe(field.get(value), depth + 1)).append(';');
        } catch (final ReflectiveOperationException | RuntimeException e) {
          out.append(field.getName()).append("=?;");
        }
      }
    }
    return out.append('}').toString();
  }

  /** Whether {@code type}, or a class it extends other than {@code Object}, declares toString. */
  private static boolean ownsToString(final Class<?> type) {
    for (Class<?> owner = type; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
      try {
        owner.getDeclaredMethod("toString");
        return true;
      } catch (final NoSuchMethodException e) {
        // not this class
      }
    }
    return false;
  }

  /** {@code name}'s {@code sample()} in {@code loader}, or null where it gives none. */
  private static Object sample(final ClassLoader loader, final String name) {
    try {
      return loader.loadClass(name).getMethod("sample").invoke(null);
    } catch (final ReflectiveOperationException e) {
      return null;
    }
  }

  /**
   * The bridge generated for {@code pair} in {@code loader}: a class form's, beside the source, or
   * a field's, named by its pair beside the class declaring the field.
   */
  private static Class<?> bridgeOf(final ClassLoader loader, final Pair pair) {
    final var target = pair.target().substring(pair.target().lastIndexOf('.') + 1);
    final var source = pair.source().substring(pair.source().lastIndexOf('.') + 1);
    final var carrierPackage = pair.carrier().substring(0, Math.max(pair.carrier().lastIndexOf('.') + 1, 0));
    for (final var name : List.of(pair.source() + "Bridge", carrierPackage + source + "To" + target + "Bridge")) {
      try {
        return loader.loadClass(name);
      } catch (final ClassNotFoundException e) {
        // the other name
      }
    }
    return null;
  }

  /** A loader over {@code compilation}'s classes and resources, as a class path holds them. */
  private static URLClassLoader loaderOf(final ProcessorHarness.Compilation compilation) throws IOException {
    final var dir = Files.createTempDirectory("field-twin");
    for (final var e : compilation.classes().entrySet()) {
      final var file = dir.resolve(e.getKey().replace('.', '/') + ".class");
      Files.createDirectories(file.getParent());
      Files.write(file, e.getValue());
    }
    for (final var e : compilation.resources().entrySet()) {
      final var file = dir.resolve(e.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, e.getValue());
    }
    return new URLClassLoader(new URL[] { dir.toUri().toURL() }, FieldTwin.class.getClassLoader());
  }

  /** The first file the two compilations generate differently, and its first differing line. */
  private static String difference(final Map<String, String> expected, final Map<String, String> actual) {
    if (!expected.keySet().equals(actual.keySet())) {
      return "class form writes " + expected.keySet() + "\nfield form writes " + actual.keySet() + "\n";
    }
    for (final var e : expected.entrySet()) {
      final var a = actual.get(e.getKey()).lines().toList();
      final var x = e.getValue().lines().toList();
      for (var i = 0; i < Math.max(a.size(), x.size()); i++) {
        final var left = i < x.size() ? x.get(i) : "";
        final var right = i < a.size() ? a.get(i) : "";
        if (!left.equals(right)) return (
          e.getKey() + " line " + (i + 1) + "\n  class: " + left + "\n  field: " + right + "\n"
        );
      }
    }
    return "";
  }

  /** {@code text} with every field bridge's name written as the class-form bridge's. */
  private static String untwinned(final String text, final Map<Pattern, String> renamed) {
    var out = text;
    for (final var e : renamed.entrySet()) out = e.getKey().matcher(out).replaceAll(e.getValue());
    return out;
  }

  /** A class-form {@code @Bridge}, the file holding it, and that file's package. */
  private record Declared(JavaFileObject file, String pkg, Annotated annotated) {}

  /**
   * The field twin of {@code sources}, recording in {@code names} each field bridge's name against
   * the class form's name for its single-target source: empty when a class-form declaration has no
   * field of the same meaning, and null when {@code sources} declare none.
   */
  private static Optional<JavaFileObject[]> twinOf(final Map<String, String> names, final JavaFileObject... sources) {
    final var codes = new LinkedHashMap<JavaFileObject, String>();
    for (final var source : sources) codes.put(source, text(source));
    final var declared = new LinkedHashMap<String, Declared>();
    for (final var e : codes.entrySet()) {
      final var found = annotatedIn(e.getValue());
      var count = 0;
      for (final var annotations = BRIDGE.matcher(e.getValue()); annotations.find(); ) count++;
      if (count != found.size()) return Optional.empty();
      for (final var annotated : found) {
        if (declared.put(annotated.name(), new Declared(e.getKey(), packageOf(e.getValue()), annotated)) != null) {
          return Optional.empty();
        }
      }
    }
    if (declared.isEmpty()) return null;
    // A source naming a bridge the class form writes refers to a class the field form names
    // otherwise.
    for (final var e : codes.entrySet()) {
      final var stripped = withoutBridges(
        e.getValue(),
        declared
          .values()
          .stream()
          .filter(d -> d.file() == e.getKey())
          .toList()
      );
      for (final var name : declared.keySet()) {
        if (Pattern.compile("\\b" + name + "(To\\w+)?Bridge\\b").matcher(stripped).find()) return Optional.empty();
      }
    }
    final var fields = new LinkedHashMap<String, List<String>>();
    for (final var d : declared.values()) {
      final var field = fieldOf(d, names);
      if (field == null) return Optional.empty();
      fields.computeIfAbsent(d.pkg(), unused -> new ArrayList<>()).add(field);
    }
    final var twin = new ArrayList<JavaFileObject>();
    for (final var e : codes.entrySet()) {
      final var own = declared
        .values()
        .stream()
        .filter(d -> d.file() == e.getKey())
        .toList();
      twin.add(ProcessorHarness.source(binaryName(e.getKey()), withoutBridges(e.getValue(), own)));
    }
    for (final var e : fields.entrySet()) {
      final var qualifier = e.getKey().isEmpty() ? "" : e.getKey() + ".";
      twin.add(
        ProcessorHarness.source(
          qualifier + CARRIER,
          (e.getKey().isEmpty() ? "" : "package " + e.getKey() + ";\n") +
            "import static io.github.eschizoid.telescope.mapping.Mapping.*;\n" +
            "@SuppressWarnings({\"rawtypes\", \"unchecked\"})\n" +
            "public final class " +
            CARRIER +
            " {\n" +
            String.join("", e.getValue()) +
            "}\n"
        )
      );
    }
    return Optional.of(twin.toArray(JavaFileObject[]::new));
  }

  /** {@code code} with the {@code @Bridge} annotations in {@code own} removed. */
  private static String withoutBridges(final String code, final List<Declared> own) {
    final var sorted = new ArrayList<>(own);
    sorted.sort((a, b) -> Integer.compare(b.annotated().start(), a.annotated().start()));
    var out = code;
    for (final var d : sorted) {
      out = out.substring(0, d.annotated().start()) + out.substring(d.annotated().argumentsEnd());
    }
    return out;
  }

  /**
   * The field declaring {@code d}'s pair with the same meaning, or null when there is none: the
   * class form's target and {@code lenient}, and no other attribute.
   */
  private static String fieldOf(final Declared d, final Map<String, String> names) {
    final var root = d.annotated();
    String target = null;
    var lenient = false;
    for (final var arg : arguments(root.arguments())) {
      final var eq = topLevelEquals(arg);
      final var key = eq < 0 ? "value" : arg.substring(0, eq).strip();
      final var value = eq < 0 ? arg.strip() : arg.substring(eq + 1).strip();
      switch (key) {
        case "value" -> {
          if (!value.endsWith(".class")) return null;
          target = value.substring(0, value.length() - ".class".length());
        }
        case "lenient" -> lenient = value.equals("true");
        default -> {
          return null;
        }
      }
    }
    if (target == null) return null;
    final var targetSimple = target.substring(target.lastIndexOf('.') + 1);
    names.put(root.name() + "To" + targetSimple + "Bridge", root.name() + "Bridge");
    final var type = lenient ? "ForwardMapper" : "Mapper";
    return (
      "  @io.github.eschizoid.telescope.annotations.Bridge\n" +
      "  public static final io.github.eschizoid.telescope.conversion." +
      type +
      " " +
      fieldName(root.name()) +
      " = io.github.eschizoid.telescope.Telescope." +
      (lenient ? "mapperForward" : "mapper") +
      "(" +
      root.name() +
      ".class, " +
      target +
      ".class);\n"
    );
  }

  /**
   * The carrier's field for the type named {@code simple}: its name with a lower-case first letter,
   * so it never hides the type a row names, and so the bridge named after it is the type's name.
   */
  private static String fieldName(final String simple) {
    return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
  }

  /**
   * A {@code @Bridge} on a type: where it starts and ends, its arguments, the type's kind and name.
   */
  private record Annotated(int start, int argumentsEnd, int end, String arguments, String kind, String name) {}

  private static List<Annotated> annotatedIn(final String code) {
    final var found = new ArrayList<Annotated>();
    final var m = BRIDGE.matcher(code);
    while (m.find()) {
      final var close = closing(code, m.end() - 1);
      if (close < 0) continue;
      final var declaration = DECLARATION.matcher(code.substring(close + 1));
      if (!declaration.find()) continue;
      found.add(
        new Annotated(
          m.start(),
          close + 1,
          close + 1 + declaration.end(),
          code.substring(m.end(), close),
          declaration.group(1),
          declaration.group(2)
        )
      );
    }
    return found;
  }

  /** The index of the parenthesis closing the one at {@code open}, skipping strings, or -1. */
  private static int closing(final String code, final int open) {
    var depth = 0;
    for (var i = open; i < code.length(); i++) {
      final var c = code.charAt(i);
      if (c == '"') {
        i++;
        while (i < code.length() && code.charAt(i) != '"') {
          if (code.charAt(i) == '\\') i++;
          i++;
        }
      } else if (c == '(' || c == '{') {
        depth++;
      } else if (c == ')' || c == '}') {
        depth--;
        if (depth == 0) return i;
      }
    }
    return -1;
  }

  /** The arguments of an annotation, split at the commas outside any nesting or string. */
  private static List<String> arguments(final String text) {
    final var out = new ArrayList<String>();
    var depth = 0;
    var from = 0;
    for (var i = 0; i < text.length(); i++) {
      final var c = text.charAt(i);
      if (c == '"') {
        i++;
        while (i < text.length() && text.charAt(i) != '"') {
          if (text.charAt(i) == '\\') i++;
          i++;
        }
      } else if (c == '(' || c == '{') {
        depth++;
      } else if (c == ')' || c == '}') {
        depth--;
      } else if (c == ',' && depth == 0) {
        out.add(text.substring(from, i));
        from = i + 1;
      }
    }
    if (!text.substring(from).isBlank()) out.add(text.substring(from));
    return out;
  }

  private static int topLevelEquals(final String arg) {
    final var eq = arg.indexOf('=');
    if (eq < 0) return -1;
    final var head = arg.substring(0, eq).strip();
    return head.matches("\\w+") ? eq : -1;
  }

  private static String packageOf(final String code) {
    final var m = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;").matcher(code);
    return m.find() ? m.group(1) : "";
  }

  private static String binaryName(final JavaFileObject source) {
    final var path = source.toUri().getPath();
    return path.substring(1, path.length() - ".java".length()).replace('/', '.');
  }

  private static String text(final JavaFileObject source) {
    try {
      return source.getCharContent(true).toString();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
