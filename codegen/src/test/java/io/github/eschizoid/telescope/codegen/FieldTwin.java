package io.github.eschizoid.telescope.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.tools.JavaFileObject;

/**
 * The same declarations written as {@code @Bridge} fields, for a compilation that declares its
 * pairs with {@code @Bridge} on their source classes.
 *
 * <p>A field's rows feed the configuration a class's {@code @Bridge} attributes feed, so the two
 * forms of one declaration owe the same generated code, and the same refusal. A gate that compares
 * a class-form bridge against the runtime mapper then holds the field form to the runtime mapper
 * too, on every shape it compiles. Every class-form {@code @Bridge} of the compilation becomes a
 * field of a carrier class in its package, so a {@code @ViaMapper} naming another of them becomes a
 * {@code via} row naming that field. The twin is built when every attribute has a row of the same
 * meaning: the target, {@code lenient}, {@code renames} and {@code drops}, a {@code @Transform}
 * naming a {@code BridgeFn} class both ways, and a {@code @ViaMapper} naming another bridge of the
 * compilation. Every other compilation is counted as having no twin.
 */
final class FieldTwin {

  private static final Pattern BRIDGE = Pattern.compile(
    "@(?:io\\.github\\.eschizoid\\.telescope\\.annotations\\.)?Bridge\\s*\\("
  );
  private static final Pattern DECLARATION = Pattern.compile(
    "^(?:\\s|@[\\w.]+(?:\\([^)]*\\))?|public|final|abstract|sealed|non-sealed|static|strictfp)*" +
      "(record|class|interface|enum)\\s+(\\w+)"
  );
  private static final Pattern MODIFIER = Pattern.compile("(Rename|Transform|ViaMapper)\\s*\\(([^)]*)\\)");
  private static final Pattern ATTRIBUTE = Pattern.compile("(\\w+)\\s*=\\s*(\"(\\w+)\"|[\\w.]+)");
  private static final Pattern QUOTED = Pattern.compile("\"(\\w+)\"");
  private static final Pattern PLACEHOLDER = Pattern.compile("%(\\w+)%:(\\w+)");
  private static final Pattern TWIN_BRIDGE = Pattern.compile("TwinCarrier(\\w+?)Bridge(?!\\w)");
  private static final String CARRIER = "TwinCarrier";

  /** How many compilations were held to a field twin, and how many had none. */
  static final AtomicInteger TWINNED = new AtomicInteger();

  static final AtomicInteger UNTWINNED = new AtomicInteger();

  private FieldTwin() {}

  /**
   * Holds the field twin of {@code sources} to {@code original}: the same generated classes, each
   * field's bridge under the name the class's had, or a refusal where the class form was refused.
   */
  static void assertAgrees(final ProcessorHarness.Compilation original, final JavaFileObject... sources) {
    final var twin = twinOf(sources);
    if (twin.isEmpty()) {
      UNTWINNED.incrementAndGet();
      return;
    }
    final var compiled = ProcessorHarness.compileFully(List.of(new BridgeProcessor()), List.of(), twin.get());
    // A class the conversion's row cannot take at its types, a generic BridgeFn or one wider than
    // the properties, which @Transform checks itself, is a carrier javac refuses: no row of the same
    // meaning exists.
    if (
      compiled
        .errors()
        .stream()
        .anyMatch(d -> d.getSource() != null && d.getSource().getName().endsWith("/" + CARRIER + ".java") && d.getMessage(Locale.ROOT).contains("cannot infer type-variable"))
    ) {
      UNTWINNED.incrementAndGet();
      return;
    }
    TWINNED.incrementAndGet();
    if (!original.success()) {
      assertFalse(
        compiled.success(),
        () -> "the class form is refused and its field twin is not:\n" + original.errorMessages()
      );
      return;
    }
    final var expected = new TreeMap<>(original.generated());
    final var actual = new TreeMap<String, String>();
    for (final var e : compiled.generated().entrySet()) {
      if (e.getKey().contains("." + CARRIER) && e.getKey().endsWith("BridgeProvider")) continue;
      if (e.getKey().startsWith(CARRIER) && e.getKey().endsWith("BridgeProvider")) continue;
      actual.put(untwinned(e.getKey()), untwinned(e.getValue()));
    }
    assertEquals(expected, actual, () -> "the field twin generates other code than the class form:\n" + difference(expected, actual) + compiled.errorMessages());
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
        if (!left.equals(right)) return e.getKey() + " line " + (i + 1) + "\n  class: " + left + "\n  field: " + right + "\n";
      }
    }
    return "";
  }

  /** {@code text} with every field bridge's name written as the class-form bridge's. */
  private static String untwinned(final String text) {
    return TWIN_BRIDGE.matcher(text).replaceAll("$1Bridge");
  }

  /** A class-form {@code @Bridge}, the file holding it, and that file's package. */
  private record Declared(JavaFileObject file, String pkg, Annotated annotated) {}

  private static Optional<JavaFileObject[]> twinOf(final JavaFileObject... sources) {
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
    if (declared.isEmpty()) return Optional.empty();
    // A source naming a bridge the class form writes refers to a class the field form names otherwise.
    for (final var e : codes.entrySet()) {
      final var stripped = withoutBridges(e.getValue(), declared.values().stream().filter(d -> d.file() == e.getKey()).toList());
      for (final var name : declared.keySet()) {
        if (Pattern.compile("\\b" + name + "(To\\w+)?Bridge\\b").matcher(stripped).find()) return Optional.empty();
      }
    }
    final var fields = new LinkedHashMap<String, List<String>>();
    for (final var d : declared.values()) {
      final var field = fieldOf(d, declared, codes);
      if (field == null) return Optional.empty();
      fields.computeIfAbsent(d.pkg(), unused -> new ArrayList<>()).add(field);
    }
    final var twin = new ArrayList<JavaFileObject>();
    for (final var e : codes.entrySet()) {
      final var own = declared.values().stream().filter(d -> d.file() == e.getKey()).toList();
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

  /** The field declaring {@code d}'s pair with rows of the same meaning, or null when there is none. */
  private static String fieldOf(
    final Declared d,
    final Map<String, Declared> declared,
    final Map<JavaFileObject, String> codes
  ) {
    final var root = d.annotated();
    String target = null;
    var lenient = false;
    final var renames = new LinkedHashMap<String, String>();
    final var drops = new ArrayList<String>();
    final var transforms = new LinkedHashMap<String, String>();
    final var methods = new LinkedHashMap<String, String>();
    final var vias = new LinkedHashMap<String, String>();
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
        case "drops" -> {
          final var m = QUOTED.matcher(value);
          while (m.find()) drops.add(m.group(1));
        }
        case "renames", "transforms", "viaMappers" -> {
          final var m = MODIFIER.matcher(value);
          var found = 0;
          while (m.find()) {
            found++;
            final var attributes = attributesOf(m.group(2));
            switch (m.group(1)) {
              case "Rename" -> {
                if (attributes.containsKey("forwardOnly")) return null;
                if (renames.put(attributes.get("source"), attributes.get("target")) != null) return null;
              }
              case "Transform" -> {
                final var using = attributes.get("using");
                if (using == null || !using.endsWith(".class")) return null;
                if (attributes.containsKey("method")) {
                  methods.put(attributes.get("field"), using.substring(0, using.length() - ".class".length()) + "::" + attributes.get("method"));
                  continue;
                }
                if ("true".equals(attributes.get("forwardOnly"))) return null;
                transforms.put(attributes.get("field"), using);
              }
              default -> {
                final var using = attributes.get("using");
                if (using == null || !using.endsWith("Bridge.class")) return null;
                final var simple = using.substring(using.lastIndexOf('.', using.length() - 7) + 1, using.length() - "Bridge.class".length());
                final var nested = declared.get(simple);
                if (nested == null) return null;
                vias.put(attributes.get("field"), (nested.pkg().isEmpty() ? "" : nested.pkg() + ".") + CARRIER + "." + fieldName(simple));
              }
            }
          }
          if (found == 0) return null;
        }
        default -> {
          return null;
        }
      }
    }
    if (target == null) return null;
    // A method converts forward only, which a mapper converting both ways refuses.
    if (!methods.isEmpty() && !lenient) return null;
    final var targetSimple = target.substring(target.lastIndexOf('.') + 1);
    final var rows = new ArrayList<String>();
    final var converted = new LinkedHashSet<String>();
    for (final var e : transforms.entrySet()) {
      final var to = renames.getOrDefault(e.getKey(), e.getKey());
      rows.add("to(%S%:" + e.getKey() + ", %T%:" + to + ", " + e.getValue() + ")");
      converted.add(e.getKey());
    }
    for (final var e : methods.entrySet()) {
      final var to = renames.getOrDefault(e.getKey(), e.getKey());
      rows.add("toOneWay(%S%:" + e.getKey() + ", %T%:" + to + ", " + e.getValue() + ")");
      converted.add(e.getKey());
    }
    for (final var e : vias.entrySet()) {
      final var to = renames.getOrDefault(e.getKey(), e.getKey());
      rows.add("via(%S%:" + e.getKey() + ", %T%:" + to + ", " + e.getValue() + ")");
      converted.add(e.getKey());
    }
    for (final var e : renames.entrySet()) {
      if (!converted.contains(e.getKey())) rows.add("to(%S%:" + e.getKey() + ", %T%:" + e.getValue() + ")");
    }
    for (final var drop : drops) rows.add("drop(%S%:" + drop + ")");
    final var resolved = new ArrayList<String>();
    for (final var row : rows) {
      final var written = accessors(row, codes, root.name(), targetSimple, target);
      if (written == null) return null;
      resolved.add(written);
    }
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
      ".class" +
      (resolved.isEmpty() ? "" : ",\n    " + String.join(",\n    ", resolved)) +
      ");\n"
    );
  }

  /**
   * The carrier's field for the type named {@code simple}: its name with a lower-case first letter,
   * so it never hides the type a row names, and so the bridge named after it is the type's name.
   */
  private static String fieldName(final String simple) {
    return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
  }

  /** The attributes of a nested annotation, by name: a quoted value unquoted, any other as written. */
  private static Map<String, String> attributesOf(final String text) {
    final var out = new LinkedHashMap<String, String>();
    final var m = ATTRIBUTE.matcher(text);
    while (m.find()) out.put(m.group(1), m.group(3) != null ? m.group(3) : m.group(2));
    return out;
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

  /**
   * {@code row} with each {@code %S%:property} and {@code %T%:property} written as a method
   * reference to the accessor of that property, or null when one has no accessor to name.
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
      final var onSource = m.group(1).equals("S");
      final var accessor = accessorOf(codes, onSource ? source : targetSimple, m.group(2));
      if (accessor == null) return null;
      m.appendReplacement(out, (onSource ? source : target) + "::" + accessor);
    }
    m.appendTail(out);
    return out.toString();
  }

  /** The accessor of {@code property} on the type named {@code type}: a component, or a getter. */
  private static String accessorOf(final Map<JavaFileObject, String> codes, final String type, final String property) {
    if (property == null) return null;
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
