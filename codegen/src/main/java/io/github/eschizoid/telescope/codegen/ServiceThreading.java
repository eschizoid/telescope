package io.github.eschizoid.telescope.codegen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Threads the services a generated bridge's rows call through the bridge's source: every static
 * method of the bridge takes them after its own parameters, every call to one of those methods,
 * and to a method of another bridge or service helper that takes services, hands them on by name,
 * each conversion of one element a container policy takes becomes a method building it over the
 * services, and the bridge's {@code BridgeFn} holds them in final fields, with the {@code bind} and
 * {@code serviceNeeds} a mapper over it calls.
 *
 * <p>A service is passed by the same name in every bridge, {@link
 * FieldRows#serviceVariable(javax.lang.model.element.TypeElement,
 * javax.lang.model.util.Elements)}, so a bridge hands a bridge it calls the services that one takes
 * without renaming them. The text the processor writes for a bridge is regular: one member per
 * line at two spaces, each parameter declared {@code final}, and no other construct of the shapes
 * matched here.
 */
final class ServiceThreading {

  private ServiceThreading() {}

  /** A service a bridge takes: its class's qualified name and the name it is passed by. */
  record Service(String type, String variable) {}

  /** One need a bridge's {@code serviceNeeds()} lists, as Java source. */
  record Need(String serviceType, String row, String in, String explain) {}

  /**
   * What another class takes: the services each of its methods named in {@code methods} take, all
   * of its static methods where {@code methods} is null, and its element conversions too.
   */
  record Callee(List<Service> services, Set<String> methods) {}

  private static final Pattern STATIC_METHOD = Pattern.compile(
    "^  (?:public |private |protected )?static (?!final )(?:<[^>]*> )?[^=(]+? (\\w+)\\((.*)\\) \\{$"
  );

  private static final Pattern NESTED_METHOD = Pattern.compile(
    "^    (?:public |private |protected )?static (?!final |class )(?:<[^>]*> )?[^=(]+? (\\w+)\\(([^)]*)\\) \\{"
  );

  private static final Pattern NESTED_CLASS = Pattern.compile(
    "^  (?:public |private |protected )?static final class (\\w+) \\{$"
  );

  private static final Pattern ELEMENTS_FIELD = Pattern.compile(
    "^  public static final io\\.github\\.eschizoid\\.telescope\\.conversion\\.ContainerCopy\\.Elements (\\w+) = (.*)$"
  );

  /**
   * {@code text}, the source of the bridge {@code simple} in {@code pkg}, threaded with {@code
   * services}; {@code callees} are the other classes, by simple and qualified name, whose methods
   * take services, and {@code needs} what the bridge's {@code BridgeFn} lists.
   */
  static String thread(
    final String text,
    final String simple,
    final String qualified,
    final List<Service> services,
    final Map<String, Callee> callees,
    final List<Need> needs
  ) {
    final var own = new LinkedHashMap<String, Callee>(callees);
    final var lines = new ArrayList<>(List.of(text.split("\n", -1)));
    final var methods = new HashSet<String>();
    final var elements = new HashSet<String>();
    for (final var line : lines) {
      final var method = STATIC_METHOD.matcher(line);
      if (method.matches()) methods.add(method.group(1));
      final var field = ELEMENTS_FIELD.matcher(line);
      if (field.matches()) elements.add(field.group(1));
    }
    final var withElements = new HashSet<>(methods);
    withElements.addAll(elements);
    own.put(simple, new Callee(services, null));
    own.put(qualified, new Callee(services, null));
    final var declared = declarations(services);
    // Declarations first: each static method takes the services after its own parameters, those
    // of a nested class holding element conversions too, which the bridge calls by the class.
    String nested = null;
    for (var i = 0; i < lines.size(); i++) {
      final var line = lines.get(i);
      final var opened = NESTED_CLASS.matcher(line);
      if (opened.matches()) {
        nested = opened.group(1).equals("Fn") ? null : opened.group(1);
        if (nested != null) own.put(nested, new Callee(services, null));
        continue;
      }
      if (line.equals("  }")) nested = null;
      if (nested != null) {
        final var inner = NESTED_METHOD.matcher(line);
        if (!inner.find()) continue;
        final var at = inner.end() - ") {".length();
        lines.set(i, line.substring(0, at) + (inner.group(2).isEmpty() ? "" : ", ") + declared + line.substring(at));
        continue;
      }
      final var method = STATIC_METHOD.matcher(line);
      if (!method.matches()) continue;
      final var params = method.group(2);
      final var at = line.lastIndexOf(") {");
      lines.set(i, line.substring(0, at) + (params.isEmpty() ? "" : ", ") + declared + line.substring(at));
    }
    // An element conversion held in a static field becomes a method building it over the services.
    for (var i = 0; i < lines.size(); i++) {
      final var field = ELEMENTS_FIELD.matcher(lines.get(i));
      if (!field.matches()) continue;
      final var name = field.group(1);
      if (field.group(2).endsWith(";")) {
        lines.set(
          i,
          "  public static io.github.eschizoid.telescope.conversion.ContainerCopy.Elements " +
            name +
            "(" +
            declared +
            ") {\n    return " +
            field.group(2) +
            "\n  }"
        );
        continue;
      }
      lines.set(
        i,
        "  public static io.github.eschizoid.telescope.conversion.ContainerCopy.Elements " +
          name +
          "(" +
          declared +
          ") {\n    return " +
          field.group(2)
      );
      for (var j = i + 1; j < lines.size(); j++) {
        if (lines.get(j).equals("  };")) {
          lines.set(j, "    };\n  }");
          break;
        }
      }
    }
    final var refusing = refusing(lines, services, needs);
    var threaded = String.join("\n", lines);
    threaded = calls(threaded, withElements, elements, services, own);
    threaded = boundFn(threaded, services, needs);
    final var end = threaded.lastIndexOf("\n}");
    return threaded.substring(0, end) + refusing + threaded.substring(end);
  }

  /**
   * Each public static method of the bridge as it was before it took services, refusing: code
   * calling the bridge directly, as a class written against a bridge with no services does, then
   * compiles and fails naming the row that needs a service. Read from the threaded declarations.
   */
  private static String refusing(final List<String> lines, final List<Service> services, final List<Need> needs) {
    final var message = quoted(unbound(needs));
    final var suffix = ", " + declarations(services) + ") {";
    final var alone = "(" + declarations(services) + ") {";
    final var out = new StringBuilder();
    for (final var line : lines) {
      if (!line.startsWith("  public static ") || !line.endsWith(") {") || line.contains("ContainerCopy.Elements ")) {
        continue;
      }
      final String original;
      if (line.endsWith(suffix)) original = line.substring(0, line.length() - suffix.length()) + ") {";
      else if (line.endsWith(alone)) original = line.substring(0, line.length() - alone.length()) + "() {";
      else continue;
      out
        .append("\n  /** Refuses: the rows of this bridge call a service, which a bound mapper hands it. */\n")
        .append(original)
        .append("\n    throw new IllegalStateException(")
        .append(message)
        .append(");\n  }\n");
    }
    return out.toString();
  }

  /** The refusal of a call on the bridge made without its services, naming the first row. */
  private static String unbound(final List<Need> needs) {
    final var first = needs.getFirst();
    return (
      first.row() +
      (first.in() == null ? "" : " in " + first.in()) +
      " calls a service, and none is bound. Inject the @Bridge field's mapper bean, or bind it with with(...)."
    );
  }

  private static String declarations(final List<Service> services) {
    final var out = new StringBuilder();
    for (final var service : services) {
      if (!out.isEmpty()) out.append(", ");
      out.append("final ").append(service.type()).append(' ').append(service.variable());
    }
    return out.toString();
  }

  private static String arguments(final List<Service> services) {
    final var out = new StringBuilder();
    for (final var service : services) {
      if (!out.isEmpty()) out.append(", ");
      out.append(service.variable());
    }
    return out.toString();
  }

  /**
   * {@code text} with the services handed to every call of {@code methods} made without a
   * qualifier, every call of a callee's methods, and every reference to an element conversion
   * turned into a call building it. String and character literals and comments are left alone.
   */
  private static String calls(
    final String text,
    final Set<String> methods,
    final Set<String> elements,
    final List<Service> services,
    final Map<String, Callee> callees
  ) {
    final var out = new StringBuilder(text.length() + 256);
    var i = 0;
    final var n = text.length();
    while (i < n) {
      final var c = text.charAt(i);
      if (c == '"' || c == '\'') {
        final var end = literalEnd(text, i);
        out.append(text, i, end);
        i = end;
        continue;
      }
      if (c == '/' && i + 1 < n && (text.charAt(i + 1) == '/' || text.charAt(i + 1) == '*')) {
        final var end = text.charAt(i + 1) == '/' ? lineEnd(text, i) : text.indexOf("*/", i + 2) + 2;
        out.append(text, i, end);
        i = end;
        continue;
      }
      if (!Character.isJavaIdentifierStart(c) || (i > 0 && Character.isJavaIdentifierPart(text.charAt(i - 1)))) {
        out.append(c);
        i++;
        continue;
      }
      // A dotted name starting here: a qualifier and a member, or a bare identifier.
      var end = i;
      while (end < n && (Character.isJavaIdentifierPart(text.charAt(end)) || text.charAt(end) == '.')) end++;
      while (end > i && text.charAt(end - 1) == '.') end--;
      final var name = text.substring(i, end);
      final var previous = previousNonSpace(text, i);
      final var dot = name.lastIndexOf('.');
      final var qualifier = dot < 0 ? null : name.substring(0, dot);
      final var member = dot < 0 ? name : name.substring(dot + 1);
      final List<Service> handed;
      if (previous == '.') {
        handed = null;
      } else if (qualifier == null) {
        handed = methods.contains(member) ? services : null;
      } else {
        final var callee = callees.get(qualifier);
        handed = callee != null && (callee.methods() == null || callee.methods().contains(member))
          ? callee.services()
          : null;
      }
      if (handed == null) {
        out.append(name);
        i = end;
        continue;
      }
      final var after = end < n ? text.charAt(end) : ' ';
      if (after == '(') {
        final var close = closing(text, end);
        final var inside = text.substring(end + 1, close);
        if (inside.startsWith("final ") || (inside.isEmpty() && text.startsWith(") {", close))) {
          out.append(name);
          i = end;
          continue;
        }
        out
          .append(name)
          .append('(')
          .append(inside)
          .append(inside.isBlank() ? "" : ", ")
          .append(arguments(handed))
          .append(')');
        i = close + 1;
        continue;
      }
      // An element conversion read as a field: its own, or another bridge's.
      final var isElements = qualifier == null
        ? elements.contains(member)
        : (member.equals("INTO_ELEMENTS") || member.equals("PATCH_INTO_ELEMENTS"));
      if (isElements && !isDeclaration(text, i)) {
        out.append(name).append('(').append(arguments(handed)).append(')');
        i = end;
        continue;
      }
      out.append(name);
      i = end;
    }
    return out.toString();
  }

  private static boolean isDeclaration(final String text, final int at) {
    final var start = text.lastIndexOf('\n', at) + 1;
    return text.substring(start, at).contains(" static ");
  }

  private static char previousNonSpace(final String text, final int at) {
    for (var i = at - 1; i >= 0; i--) {
      final var c = text.charAt(i);
      if (!Character.isWhitespace(c)) return c;
    }
    return ' ';
  }

  private static int literalEnd(final String text, final int start) {
    final var quote = text.charAt(start);
    var i = start + 1;
    while (i < text.length()) {
      final var c = text.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == quote) return i + 1;
      i++;
    }
    return text.length();
  }

  private static int lineEnd(final String text, final int start) {
    final var end = text.indexOf('\n', start);
    return end < 0 ? text.length() : end;
  }

  /** The index of the parenthesis closing the one at {@code open}. */
  private static int closing(final String text, final int open) {
    var depth = 0;
    var i = open;
    while (i < text.length()) {
      final var c = text.charAt(i);
      if (c == '"' || c == '\'') {
        i = literalEnd(text, i);
        continue;
      }
      if (c == '(') depth++;
      else if (c == ')' && --depth == 0) return i;
      i++;
    }
    throw new IllegalStateException("unbalanced parentheses in a generated bridge");
  }

  /**
   * {@code text} with the bridge's {@code BridgeFn} class holding the services in final fields,
   * answering {@code serviceNeeds} and {@code bind}, and its constant built bound to none.
   */
  private static String boundFn(final String text, final List<Service> services, final List<Need> needs) {
    final var header = text.indexOf("  private static final class Fn implements BridgeFn<");
    if (header < 0) return text;
    final var open = text.indexOf("{\n", header) + 2;
    final var close = text.indexOf("\n  }\n", open);
    final var fields = new StringBuilder();
    for (final var service : services) {
      fields.append("    private final ").append(service.type()).append(' ').append(service.variable()).append(";\n");
    }
    fields.append("\n    Fn(").append(declarations(services)).append(") {\n");
    for (final var service : services) {
      fields.append("      this.").append(service.variable()).append(" = ").append(service.variable()).append(";\n");
    }
    fields.append("    }\n\n");
    final var methods = new StringBuilder("\n\n");
    methods
      .append("    @Override\n")
      .append("    public java.util.List<io.github.eschizoid.telescope.conversion.ServiceNeeds.Need> serviceNeeds() {\n")
      .append("      return java.util.List.of(");
    for (var i = 0; i < needs.size(); i++) {
      final var need = needs.get(i);
      if (i > 0) methods.append(", ");
      methods
        .append("new io.github.eschizoid.telescope.conversion.ServiceNeeds.Need(")
        .append(need.serviceType())
        .append(".class, ")
        .append(quoted(need.row()))
        .append(", ")
        .append(need.in() == null ? "null" : quoted(need.in()))
        .append(", ")
        .append(need.explain() == null ? "null" : quoted(need.explain()))
        .append(')');
    }
    methods.append(");\n    }\n\n");
    methods
      .append("    @Override\n")
      .append("    public BridgeFn<")
      .append(text, header + "  private static final class Fn implements BridgeFn<".length(), text.indexOf("> {", header))
      .append("> bind(final Object[] services) {\n")
      .append("      return new Fn(");
    for (var i = 0; i < services.size(); i++) {
      if (i > 0) methods.append(", ");
      methods.append('(').append(services.get(i).type()).append(") services[").append(i).append(']');
    }
    methods.append(");\n    }");
    final var nulls = new StringBuilder();
    for (var i = 0; i < services.size(); i++) nulls.append(i > 0 ? ", null" : "null");
    // The constant built bound to none refuses each operation that would hand a service on.
    final var message = quoted(unbound(needs));
    final var body = new StringBuilder();
    for (final var line : text.substring(open, close).split("\n", -1)) {
      var guarded = line;
      if (line.startsWith("      return ")) for (final var service : services) {
        guarded = guarded.replaceAll(
          "(?<=[(, ])" + Pattern.quote(service.variable()) + "(?=[),])",
          "java.util.Objects.requireNonNull(" + service.variable() + ", " + message.replace("\\", "\\\\").replace("$", "\\$") + ")"
        );
      }
      if (!body.isEmpty()) body.append('\n');
      body.append(guarded);
    }
    return (
      text.substring(0, open) +
      fields +
      body +
      methods +
      text.substring(close).replace("new Fn();", "new Fn(" + nulls + ");")
    );
  }

  /** {@code value} as a Java string literal. */
  static String quoted(final String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
