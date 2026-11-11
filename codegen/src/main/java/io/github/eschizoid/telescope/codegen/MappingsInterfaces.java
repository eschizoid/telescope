package io.github.eschizoid.telescope.codegen;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * The {@code <Type>Mappings} interfaces {@code BridgeProcessor} writes, through which a value
 * converts itself: {@code invoice.toInvoiceDto()} for a {@code @Bridge} field from {@code Invoice}
 * to {@code InvoiceDto}.
 *
 * <p>A type compiled in this compilation gets one interface, in its own package, when it is the
 * source of a {@code @Bridge} field or the target of a two-way one. Each such field adds one
 * default method named {@code to} and the other side's simple name, which calls the field's {@code
 * forward} on the source's interface and its {@code backward} on the target's. The method reads the
 * field and makes one call, so it costs what calling the field costs. A type that wants the call
 * form names the interface in its {@code implements} clause, which javac resolves once a round has
 * written it; the interface is therefore written in the round its fields are first seen, never in
 * the final one, whose output the types compiled with it cannot name.
 *
 * <p>The method is left out where the type declares or inherits a method of that name taking
 * nothing, which then is what the call means; a type naming the interface in its {@code implements}
 * clause gets a note on the field when the method is inherited. It is also left out where the
 * interface cannot reach the field or a side from its package, where two fields would give one
 * interface two methods of one name, which leaves out both, and where a source a processor writes
 * declares the field after the interface was written. A type naming the interface in its {@code
 * implements} clause hears about either on the field; a type that does not name it calls no method
 * of it, so its fields compile as they would without the interface.
 */
final class MappingsInterfaces {

  /** What the interface's simple name adds to its type's. */
  static final String SUFFIX = "Mappings";

  private static final String MAPPER = "io.github.eschizoid.telescope.conversion.Mapper";

  /**
   * One default method: {@code returns name() { return call((takes) (Object) this); }}, added by
   * {@code field}.
   */
  private record Method(
    String name,
    VariableElement field,
    String returns,
    String takes,
    String call,
    boolean raw,
    boolean deprecated
  ) {}

  private final ProcessingEnvironment env;

  /** The methods each interface declares, by its type's qualified name, then by method name. */
  private final Map<String, Map<String, Method>> methods = new LinkedHashMap<>();

  /** The type each interface is for, by its qualified name. */
  private final Map<String, TypeElement> owners = new LinkedHashMap<>();

  /**
   * The fields whose mapper type names a class no round has written yet, as {@code Carrier#FIELD}.
   */
  private final Set<String> unresolved = new LinkedHashSet<>();

  /** The interfaces written, by their type's qualified name. */
  private final Set<String> written = new HashSet<>();

  /**
   * The methods two fields would give one interface, as {@code Type#name}, each with the first of
   * them; the interface declares none of them.
   */
  private final Map<String, Method> shared = new HashMap<>();

  MappingsInterfaces(final ProcessingEnvironment env) {
    this.env = env;
  }

  /** The qualified name of the interface for {@code type}. */
  static String interfaceName(final TypeElement type, final ProcessingEnvironment env) {
    final var pkg = env.getElementUtils().getPackageOf(type).getQualifiedName().toString();
    return (pkg.isEmpty() ? "" : pkg + ".") + type.getSimpleName() + SUFFIX;
  }

  /**
   * Reads the {@code @Bridge} fields among {@code annotated}, the elements a round reports, and
   * again each field an earlier round could not read because its mapper type named a class not yet
   * written. A round reports each element once, in the round that enters its source.
   */
  void read(final Set<? extends Element> annotated) {
    final var fields = new ArrayList<VariableElement>();
    for (final var key : List.copyOf(unresolved)) {
      final var at = key.lastIndexOf('#');
      final var carrier = env.getElementUtils().getTypeElement(key.substring(0, at));
      for (final var field : ElementFilter.fieldsIn(carrier.getEnclosedElements())) {
        if (field.getSimpleName().contentEquals(key.substring(at + 1))) fields.add(field);
      }
    }
    unresolved.clear();
    fields.addAll(ElementFilter.fieldsIn(annotated));
    for (final var field : fields) add(field);
  }

  /**
   * Writes every interface not yet written, unless a field still waits on a class a later round
   * writes, or this is the final round, whose output the types compiled with it cannot name.
   */
  void write(final boolean finalRound) {
    if (finalRound || !unresolved.isEmpty()) return;
    for (final var entry : methods.entrySet()) {
      if (written.add(entry.getKey())) write(owners.get(entry.getKey()), entry.getValue().values());
    }
  }

  private static String keyOf(final VariableElement field) {
    return ((TypeElement) field.getEnclosingElement()).getQualifiedName() + "#" + field.getSimpleName();
  }

  /**
   * Adds the methods {@code field} gives. A field whose type does not name both sides, a raw {@code
   * Mapper} or {@code Mapper<?, ?>}, gives none, since the interface could not type the call. A
   * side is a class literal's class, so the type names each one without type arguments.
   */
  private void add(final VariableElement field) {
    final var carrier = (TypeElement) field.getEnclosingElement();
    // A field holding a mapper call is a Mapper or a ForwardMapper, the only types of two type
    // arguments either factory's result is assignable to; Object and a raw Mapper have none.
    if (!(field.asType() instanceof DeclaredType declared) || declared.getTypeArguments().size() != 2) return;
    final var mapperType = ((TypeElement) declared.asElement()).getQualifiedName().toString();
    final var source = declared.getTypeArguments().get(0);
    final var target = declared.getTypeArguments().get(1);
    if (source.getKind() == TypeKind.ERROR || target.getKind() == TypeKind.ERROR) {
      unresolved.add(keyOf(field));
      return;
    }
    if (!(source instanceof DeclaredType s) || !(target instanceof DeclaredType t)) return;
    final var sourceType = (TypeElement) s.asElement();
    final var targetType = (TypeElement) t.asElement();
    final var call = carrier.getQualifiedName() + "." + field.getSimpleName();
    contribute(sourceType, targetType, field, call + ".forward");
    // A pair of one type converts to itself in both directions, so its backward has no name of its
    // own on the one interface.
    if (mapperType.equals(MAPPER) && !sourceType.equals(targetType)) {
      contribute(targetType, sourceType, field, call + ".backward");
    }
  }

  /**
   * Adds to {@code owner}'s interface the method converting it to {@code other} through {@code
   * call}.
   */
  private void contribute(
    final TypeElement owner,
    final TypeElement other,
    final VariableElement field,
    final String call
  ) {
    if (!compiledHere(owner)) return;
    final var ownerName = owner.getQualifiedName().toString();
    // The interface is written for every type a field converts, whatever methods it ends up with,
    // so a type naming it in its implements clause compiles once it declares every method itself.
    final var declared = methods.computeIfAbsent(ownerName, unused -> new LinkedHashMap<>());
    owners.putIfAbsent(ownerName, owner);
    final var name = "to" + other.getSimpleName();
    if (written.contains(ownerName)) {
      if (implementsInterface(owner)) error(field, writtenEarlier(field, owner, name));
      return;
    }
    final var own = noArgument(owner, name);
    if (own != null) {
      final var from = (TypeElement) own.getEnclosingElement();
      if (!from.equals(owner) && implementsInterface(owner)) note(field, inherited(field, owner, name, from));
      return;
    }
    final var pkg = env.getElementUtils().getPackageOf(owner);
    final var unreachable = unreachable(field, List.of(owner, other), pkg);
    if (unreachable != null) {
      if (implementsInterface(owner)) error(field, unreachable(owner, name, unreachable, pkg));
      return;
    }
    final var key = ownerName + "#" + name;
    final var held = shared.containsKey(key) ? shared.get(key) : declared.remove(name);
    if (held != null) {
      shared.putIfAbsent(key, held);
      if (implementsInterface(owner)) {
        error(field, sameName(held.field(), field, owner, name, held.returns(), other.getQualifiedName().toString()));
      }
      return;
    }
    final var raw = !owner.getTypeParameters().isEmpty() || !other.getTypeParameters().isEmpty();
    final var elements = env.getElementUtils();
    final var deprecated =
      elements.isDeprecated(field) ||
      elements.isDeprecated(field.getEnclosingElement()) ||
      elements.isDeprecated(owner) ||
      elements.isDeprecated(other);
    declared.put(name, new Method(name, field, other.getQualifiedName().toString(), ownerName, call, raw, deprecated));
  }

  /** Whether {@code type} was compiled from a source in this compilation, not read from a class. */
  private boolean compiledHere(final TypeElement type) {
    // Elements.getFileObjectOf answers null for an element it has no file for.
    final var file = env.getElementUtils().getFileObjectOf(type);
    return file != null && file.getKind() == JavaFileObject.Kind.SOURCE;
  }

  /**
   * The method named {@code name} taking no argument {@code type} declares or inherits, or null.
   */
  private ExecutableElement noArgument(final TypeElement type, final String name) {
    for (final var method : ElementFilter.methodsIn(env.getElementUtils().getAllMembers(type))) {
      if (method.getSimpleName().contentEquals(name) && method.getParameters().isEmpty()) return method;
    }
    return null;
  }

  /** Whether {@code type} names its interface among its direct supertypes. */
  private boolean implementsInterface(final TypeElement type) {
    final var simple = type.getSimpleName() + SUFFIX;
    final var qualified = interfaceName(type, env);
    for (final var named : type.getInterfaces()) {
      final var text = named.toString();
      if (text.equals(simple) || text.equals(qualified)) return true;
    }
    return false;
  }

  /**
   * What the interface, in {@code pkg}, cannot reach of {@code field} and {@code sides} — the
   * field, its class or one of the sides — named for a diagnostic, or null when it reaches all.
   */
  private String unreachable(final VariableElement field, final List<TypeElement> sides, final PackageElement pkg) {
    final var carrier = (TypeElement) field.getEnclosingElement();
    final var fieldName = carrier.getSimpleName() + "." + field.getSimpleName();
    if (field.getModifiers().contains(Modifier.PRIVATE)) return "the field " + fieldName + ", which is private,";
    if (!reaches(carrier, pkg)) return "the class " + carrier.getQualifiedName() + ", which is not public,";
    if (!reaches(field, pkg)) return "the field " + fieldName + ", which is not public,";
    for (final var side : sides) {
      if (!reaches(side, pkg)) return "the type " + side.getQualifiedName() + ", which is not public,";
    }
    return null;
  }

  /** Whether code in {@code pkg} can name {@code element}, a top-level class or a field of one. */
  private boolean reaches(final Element element, final PackageElement pkg) {
    return (
      element.getModifiers().contains(Modifier.PUBLIC) || env.getElementUtils().getPackageOf(element).equals(pkg)
    );
  }

  private void write(final TypeElement owner, final Iterable<Method> declared) {
    final var name = interfaceName(owner, env);
    final var dot = name.lastIndexOf('.');
    final var simple = name.substring(dot + 1);
    final var origins = new ArrayList<Element>();
    origins.add(owner);
    final var suppressed = new TreeSet<String>();
    for (final var method : declared) {
      origins.add(method.field());
      if (method.raw()) suppressed.add("rawtypes");
      // A use of a deprecated element warns under -Xlint:deprecation, and of one deprecated for
      // removal by default, so a build under -Werror fails on code it did not write.
      if (method.deprecated()) {
        suppressed.add("deprecation");
        suppressed.add("removal");
      }
    }
    try {
      final var file = env.getFiler().createSourceFile(name, origins.toArray(Element[]::new));
      try (final var out = new PrintWriter(file.openWriter())) {
        if (dot > 0) {
          out.println("package " + name.substring(0, dot) + ";");
          out.println();
        }
        out.println("/**");
        out.println(
          " * Generated by telescope-codegen: the conversions the {@code @Bridge} fields of " +
            owner.getSimpleName() +
            " declare, called"
        );
        out.println(" * on the value itself by a type that names this interface in its {@code implements} clause.");
        out.println(" */");
        if (!suppressed.isEmpty()) {
          out.println(
            "@SuppressWarnings(" +
              suppressed
                .stream()
                .map(s -> "\"" + s + "\"")
                .collect(
                  Collectors.joining(", ", suppressed.size() > 1 ? "{ " : "", suppressed.size() > 1 ? " }" : "")
                ) +
              ")"
          );
        }
        out.println((owner.getModifiers().contains(Modifier.PUBLIC) ? "public " : "") + "interface " + simple + " {");
        for (final var method : declared) {
          out.println();
          out.println("  /** Returns {@code " + method.call() + "(this)}. */");
          out.println("  default " + method.returns() + " " + method.name() + "() {");
          out.println("    return " + method.call() + "((" + method.takes() + ") (Object) this);");
          out.println("  }");
        }
        out.println("}");
      }
    } catch (final FilerException e) {
      // A type of this name already exists in the compilation; the name is the user's.
    } catch (final IOException e) {
      error(owner, "Failed to write " + name + ": " + e.getMessage());
    }
  }

  private void error(final Element at, final String message) {
    env.getMessager().printMessage(Diagnostic.Kind.ERROR, message, at);
  }

  private void note(final Element at, final String message) {
    env.getMessager().printMessage(Diagnostic.Kind.NOTE, message, at);
  }

  private static String fieldName(final VariableElement field) {
    return "@Bridge field " + field.getEnclosingElement().getSimpleName() + "." + field.getSimpleName();
  }

  /** Two fields giving {@code owner}'s interface a method named {@code name} each. */
  static String sameName(
    final VariableElement first,
    final VariableElement second,
    final TypeElement owner,
    final String name,
    final String firstReturns,
    final String secondReturns
  ) {
    final var iface = owner.getSimpleName() + SUFFIX;
    return (
      owner.getSimpleName() +
      " implements the generated " +
      iface +
      ", and " +
      fieldName(second) +
      " would give " +
      iface +
      " a second method " +
      name +
      "(), which " +
      fieldName(first) +
      " already gives it" +
      (firstReturns.equals(secondReturns)
        ? ", both returning " + firstReturns
        : ", returning " + firstReturns + " where this one returns " + secondReturns) +
      ". One interface cannot declare two methods of one name. Declare " +
      name +
      "() on " +
      owner.getSimpleName() +
      " yourself, returning the conversion that name should mean, and " +
      iface +
      " leaves out both; call the other through its field, or through a method of your own under another name"
    );
  }

  /**
   * A field whose method {@code owner}'s interface, which {@code owner} implements, cannot call.
   */
  static String unreachable(final TypeElement owner, final String name, final String what, final PackageElement pkg) {
    final var iface = owner.getSimpleName() + SUFFIX;
    return (
      owner.getSimpleName() +
      " implements the generated " +
      iface +
      ", which cannot reach " +
      what +
      " from package " +
      (pkg.isUnnamed() ? "(unnamed)" : pkg.getQualifiedName()) +
      ", so it has no method " +
      name +
      "(). Make it reachable from there, or declare " +
      name +
      "() on " +
      owner.getSimpleName() +
      " yourself"
    );
  }

  /** A field whose method {@code owner} inherits under the same name from {@code from}. */
  static String inherited(
    final VariableElement field,
    final TypeElement owner,
    final String name,
    final TypeElement from
  ) {
    return (
      owner.getSimpleName() +
      " inherits " +
      name +
      "() from " +
      from.getQualifiedName() +
      ", so " +
      owner.getSimpleName() +
      SUFFIX +
      " has no method " +
      name +
      "() for " +
      fieldName(field) +
      ", and the call means the inherited one"
    );
  }

  /** A field read after {@code owner}'s interface was written, in a source a processor wrote. */
  static String writtenEarlier(final VariableElement field, final TypeElement owner, final String name) {
    return (
      fieldName(field) +
      " is declared in a source written during annotation processing, after " +
      owner.getSimpleName() +
      SUFFIX +
      " was generated, so the interface has no method " +
      name +
      "() for it. Call the field, or declare it in a source the build compiles"
    );
  }
}
