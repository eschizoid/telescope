package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.tools.JavaFileObject;

/**
 * The same declaration written as a {@code @Bridge} field, for a compilation that declares a pair
 * with a {@code @Bridge} on its source class.
 *
 * <p>A field's rows feed the configuration a class's {@code @Bridge} attributes feed, so the two
 * forms of one declaration owe the same generated code, and the same refusal. A gate that compares
 * a class-form bridge against the runtime mapper then holds the field form to the runtime mapper
 * too, on every shape it compiles. The field twin is built for a {@code @Bridge} whose attributes
 * have a row of the same meaning: its target, {@code lenient}, {@code renames} and {@code drops}.
 * Every other compilation is counted as having no twin.
 */
final class FieldTwin {

  private static final Pattern BRIDGE = Pattern.compile(
    "@(?:io\\.github\\.eschizoid\\.telescope\\.annotations\\.)?Bridge\\s*\\("
  );
  private static final Pattern DECLARATION = Pattern.compile(
    "^(?:\\s|@[\\w.]+(?:\\([^)]*\\))?|public|final|abstract|sealed|non-sealed|static|strictfp)*" +
      "(record|class|interface|enum)\\s+(\\w+)"
  );
  private static final Pattern RENAME = Pattern.compile(
    "Rename\\s*\\(\\s*source\\s*=\\s*\"(\\w+)\"\\s*,\\s*target\\s*=\\s*\"(\\w+)\"\\s*\\)"
  );
  private static final Pattern QUOTED = Pattern.compile("\"(\\w+)\"");

  /** How many compilations were held to a field twin, and how many had none. */
  static final AtomicInteger TWINNED = new AtomicInteger();

  static final AtomicInteger UNTWINNED = new AtomicInteger();

  private FieldTwin() {}

  /** A source class's {@code @Bridge} as a field: the sources to compile, and the bridge names. */
  private record Twin(JavaFileObject[] sources, String fieldBridge, String classBridge) {}

  /**
   * Holds the field twin of {@code sources} to {@code original}: the same generated classes, the
   * field's bridge under the name the class's had, or a refusal where the class form was refused.
   */
  static void assertAgrees(final ProcessorHarness.Compilation original, final JavaFileObject... sources) {
    final var twin = twinOf(sources);
    if (twin.isEmpty()) {
      UNTWINNED.incrementAndGet();
      return;
    }
    TWINNED.incrementAndGet();
    final var compiled = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), twin.get().sources());
    if (!original.success()) {
      assertFalse(
        compiled.success(),
        () -> "the class form is refused and its field twin is not:\n" + original.errorMessages()
      );
      return;
    }
    final var expected = new TreeMap<>(original.generated());
    final var actual = new TreeMap<String, String>();
    final var field = twin.get().fieldBridge();
    final var named = twin.get().classBridge();
    for (final var e : compiled.generated().entrySet()) {
      if (e.getKey().equals(field + "Provider")) continue;
      actual.put(e.getKey().equals(field) ? named : e.getKey(), e.getValue().replace(simple(field), simple(named)));
    }
    assertEquals(
      expected,
      actual,
      () -> "the field twin generates other code than the class form:\n" + compiled.errorMessages()
    );
  }

  private static String simple(final String qualified) {
    return qualified.substring(qualified.lastIndexOf('.') + 1);
  }

  private static Optional<Twin> twinOf(final JavaFileObject... sources) {
    final var codes = new LinkedHashMap<JavaFileObject, String>();
    for (final var source : sources) codes.put(source, text(source));
    JavaFileObject rootFile = null;
    Annotated root = null;
    for (final var e : codes.entrySet()) {
      for (final var annotated : annotatedIn(e.getValue())) {
        if (root == null || (!root.name().endsWith("Src") && annotated.name().endsWith("Src"))) {
          root = annotated;
          rootFile = e.getKey();
        }
      }
    }
    if (root == null) return Optional.empty();
    final var code = codes.get(rootFile);
    final var annotations = BRIDGE.matcher(code);
    var count = 0;
    while (annotations.find()) count++;
    if (count != 1) return Optional.empty();
    for (final var e : codes.entrySet()) {
      if (e.getKey() != rootFile && e.getValue().contains(root.name() + "Bridge")) return Optional.empty();
    }
    final var args = arguments(root.arguments());
    String target = null;
    var lenient = false;
    final var rows = new ArrayList<String>();
    for (final var arg : args) {
      final var eq = topLevelEquals(arg);
      final var key = eq < 0 ? "value" : arg.substring(0, eq).strip();
      final var value = eq < 0 ? arg.strip() : arg.substring(eq + 1).strip();
      switch (key) {
        case "value" -> {
          if (!value.endsWith(".class")) return Optional.empty();
          target = value.substring(0, value.length() - ".class".length());
        }
        case "lenient" -> lenient = value.equals("true");
        case "renames" -> {
          if (value.contains("forwardOnly")) return Optional.empty();
          final var m = RENAME.matcher(value);
          var found = 0;
          while (m.find()) {
            rows.add("to(%SOURCE%:" + m.group(1) + ", %TARGET%:" + m.group(2) + ")");
            found++;
          }
          if (found == 0) return Optional.empty();
        }
        case "drops" -> {
          final var m = QUOTED.matcher(value);
          while (m.find()) rows.add("drop(%SOURCE%:" + m.group(1) + ")");
        }
        default -> {
          return Optional.empty();
        }
      }
    }
    if (target == null) return Optional.empty();
    final var targetSimple = target.substring(target.lastIndexOf('.') + 1);
    final var resolved = new ArrayList<String>();
    for (final var row : rows) {
      final var written = accessors(row, codes, root.name(), targetSimple, target);
      if (written == null) return Optional.empty();
      resolved.add(written);
    }
    rows.clear();
    rows.addAll(resolved);
    final var pkg = packageOf(code);
    final var carrier = root.name() + "FieldCarrier";
    final var factory = lenient ? "mapperForward" : "mapper";
    final var type = lenient ? "ForwardMapper" : "Mapper";
    final var carrierCode =
      (pkg.isEmpty() ? "" : "package " + pkg + ";\n") +
      "import static io.github.eschizoid.telescope.mapping.Mapping.*;\n" +
      "public final class " +
      carrier +
      " {\n" +
      "  @io.github.eschizoid.telescope.annotations.Bridge\n" +
      "  @SuppressWarnings(\"rawtypes\")\n" +
      "  public static final io.github.eschizoid.telescope.conversion." +
      type +
      " SRC = io.github.eschizoid.telescope.Telescope." +
      factory +
      "(" +
      root.name() +
      ".class, " +
      target +
      ".class" +
      (rows.isEmpty() ? "" : ",\n    " + String.join(",\n    ", rows).replace("%TARGET%", target)) +
      ");\n}\n";
    final var twinSources = new ArrayList<JavaFileObject>();
    for (final var e : codes.entrySet()) {
      final var text =
        e.getKey() == rootFile ? code.substring(0, root.start()) + code.substring(root.argumentsEnd()) : e.getValue();
      twinSources.add(ProcessorHarness.source(binaryName(e.getKey()), text));
    }
    final var qualifier = pkg.isEmpty() ? "" : pkg + ".";
    twinSources.add(ProcessorHarness.source(qualifier + carrier, carrierCode));
    return Optional.of(
      new Twin(
        twinSources.toArray(JavaFileObject[]::new),
        qualifier + carrier + "SrcBridge",
        qualifier + root.name() + "Bridge"
      )
    );
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

  private static final Pattern PLACEHOLDER = Pattern.compile("%(SOURCE|TARGET)%:(\\w+)");

  /**
   * {@code row} with each {@code %SOURCE%:property} and {@code %TARGET%:property} written as a
   * method reference to the accessor of that property, or null when one has no accessor to name.
   */
  private static String accessors(
    final String row,
    final Map<JavaFileObject, String> codes,
    final String source,
    final String targetSimple,
    final String target
  ) {
    final var m = PLACEHOLDER.matcher(row);
    final var out = new StringBuilder();
    while (m.find()) {
      final var side = m.group(1).equals("SOURCE") ? source : targetSimple;
      final var accessor = accessorOf(codes, side, m.group(2));
      if (accessor == null) return null;
      m.appendReplacement(out, (m.group(1).equals("SOURCE") ? source : target) + "::" + accessor);
    }
    m.appendTail(out);
    return out.toString();
  }

  /** The accessor of {@code property} on the type named {@code type}: a component, or a getter. */
  private static String accessorOf(final Map<JavaFileObject, String> codes, final String type, final String property) {
    if (declaresRecord(codes, type)) return property;
    final var cap = Character.toUpperCase(property.charAt(0)) + property.substring(1);
    final var getter = Pattern.compile("\\b(get|is)" + cap + "\\s*\\(\\s*\\)");
    final var declaration = Pattern.compile("\\bclass\\s+" + type + "\\b");
    for (final var code : codes.values()) {
      if (!declaration.matcher(code).find()) continue;
      final var found = getter.matcher(code);
      return found.find() ? found.group(1) + cap : null;
    }
    return null;
  }

  private static boolean declaresRecord(final Map<JavaFileObject, String> codes, final String name) {
    final var record = Pattern.compile("\\brecord\\s+" + name + "\\b");
    for (final var code : codes.values()) if (record.matcher(code).find()) return true;
    return false;
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
