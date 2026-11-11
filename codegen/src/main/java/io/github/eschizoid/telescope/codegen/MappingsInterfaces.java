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
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
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
 * <p>The method is left out, with nothing reported, where the type declares or inherits a method of
 * that name taking nothing, which then is what the call means. It is also left out where the
 * interface cannot reach the field or a type the method names from its package, and where two
 * fields would give one interface two methods of one name, which leaves out both. A type naming the
 * interface in its {@code implements} clause hears about either on the field; a type that does not
 * name it calls no method of it, so its fields compile as they would without the interface.
 */
final class MappingsInterfaces {

  /** What the interface's simple name adds to its type's. */
  static final String SUFFIX = "Mappings";

  private static final String MAPPER = "io.github.eschizoid.telescope.conversion.Mapper";
  private static final String FORWARD_MAPPER = "io.github.eschizoid.telescope.conversion.ForwardMapper";

  /**
   * One default method: {@code returns name() { return call((takes) (Object) this); }}, added by
   * {@code field}.
   */
  private record Method(String name, VariableElement field, String returns, String takes, String call, boolean raw) {}

  private final ProcessingEnvironment env;

  /** The methods each interface declares, by its type's qualified name, then by method name. */
  private final Map<String, Map<String, Method>> methods = new LinkedHashMap<>();

  /** The type each interface is for, by its qualified name. */
  private final Map<String, TypeElement> owners = new LinkedHashMap<>();

  /** The fields read so far, as {@code Carrier#FIELD}. */
  private final Set<String> read = new HashSet<>();

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
   * written.
   */
  void read(final Set<? extends Element> annotated) {
    final var fields = new ArrayList<VariableElement>();
    for (final var key : List.copyOf(unresolved)) {
      final var at = key.lastIndexOf('#');
      final var carrier = env.getElementUtils().getTypeElement(key.substring(0, at));
      if (carrier == null) continue;
      for (final var field : ElementFilter.fieldsIn(carrier.getEnclosedElements())) {
        if (field.getSimpleName().contentEquals(key.substring(at + 1))) fields.add(field);
      }
    }
    unresolved.clear();
    for (final var element : annotated) {
      if (element.getKind() == ElementKind.FIELD && read.add(keyOf((VariableElement) element))) {
        fields.add((VariableElement) element);
      }
    }
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

  private void add(final VariableElement field) {
    if (!(field.getEnclosingElement() instanceof TypeElement carrier)) return;
    if (!(field.asType() instanceof DeclaredType declared) || declared.getTypeArguments().size() != 2) return;
    final var mapperType = ((TypeElement) declared.asElement()).getQualifiedName().toString();
    if (!mapperType.equals(MAPPER) && !mapperType.equals(FORWARD_MAPPER)) return;
    final var source = declared.getTypeArguments().get(0);
    final var target = declared.getTypeArguments().get(1);
    if (source.getKind() == TypeKind.ERROR || target.getKind() == TypeKind.ERROR) {
      unresolved.add(keyOf(field));
      return;
    }
    if (
      !(source instanceof DeclaredType s) ||
      !(s.asElement() instanceof TypeElement sourceType) ||
      !(target instanceof DeclaredType t) ||
      !(t.asElement() instanceof TypeElement targetType)
    ) {
      return;
    }
    final var call = carrier.getQualifiedName() + "." + field.getSimpleName();
    contribute(sourceType, targetType, field, source, target, call + ".forward");
    // A pair of one type converts to itself in both directions, so its backward has no name of its
    // own on the one interface.
    if (mapperType.equals(MAPPER) && !sourceType.equals(targetType)) {
      contribute(targetType, sourceType, field, target, source, call + ".backward");
    }
  }

  /**
   * Adds to {@code owner}'s interface the method converting it, as {@code takes}, to {@code other},
   * as {@code returns}, through {@code call}.
   */
  private void contribute(
    final TypeElement owner,
    final TypeElement other,
    final VariableElement field,
    final TypeMirror takes,
    final TypeMirror returns,
    final String call
  ) {
    if (owner.getNestingKind() != NestingKind.TOP_LEVEL || !compiledHere(owner)) return;
    final var ownerName = owner.getQualifiedName().toString();
    // The interface is written for every type a field converts, whatever methods it ends up with,
    // so a type naming it in its implements clause compiles once it declares every method itself.
    final var declared = methods.computeIfAbsent(ownerName, unused -> new LinkedHashMap<>());
    owners.putIfAbsent(ownerName, owner);
    final var name = "to" + other.getSimpleName();
    if (declaresNoArgument(owner, name)) return;
    final var pkg = env.getElementUtils().getPackageOf(owner);
    final var unreachable = unreachable(field, List.of(takes, returns), pkg);
    if (unreachable != null) {
      if (implementsInterface(owner)) {
        error(field, unreachable(owner, name, unreachable, pkg));
      }
      return;
    }
    if (written.contains(ownerName)) {
      error(field, writtenEarlier(field, owner, name));
      return;
    }
    final var key = ownerName + "#" + name;
    final var held = shared.containsKey(key) ? shared.get(key) : declared.remove(name);
    if (held != null) {
      shared.putIfAbsent(key, held);
      if (implementsInterface(owner)) {
        error(field, sameName(held.field(), field, owner, name, held.returns(), render(returns)));
      }
      return;
    }
    final var raw = AbstractTelescopeProcessor.namesRaw(takes) || AbstractTelescopeProcessor.namesRaw(returns);
    declared.put(name, new Method(name, field, render(returns), render(takes), call, raw));
  }

  /** Whether {@code type} was compiled from a source in this compilation, not read from a class. */
  private boolean compiledHere(final TypeElement type) {
    final var file = env.getElementUtils().getFileObjectOf(type);
    return file != null && file.getKind() == JavaFileObject.Kind.SOURCE;
  }

  /** Whether {@code type} declares or inherits a method named {@code name} taking no argument. */
  private boolean declaresNoArgument(final TypeElement type, final String name) {
    for (final var method : ElementFilter.methodsIn(env.getElementUtils().getAllMembers(type))) {
      if (method.getSimpleName().contentEquals(name) && method.getParameters().isEmpty()) return true;
    }
    return false;
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
   * What the interface, in {@code pkg}, cannot reach of {@code field} and {@code types} — the
   * field, its class or one of the types — named for a diagnostic, or null when it reaches all.
   */
  private String unreachable(final VariableElement field, final List<TypeMirror> types, final PackageElement pkg) {
    final var carrier = (TypeElement) field.getEnclosingElement();
    final var fieldName = carrier.getSimpleName() + "." + field.getSimpleName();
    if (field.getModifiers().contains(Modifier.PRIVATE)) return "the field " + fieldName + ", which is private,";
    if (!reaches(carrier, pkg)) return "the class " + carrier.getQualifiedName() + ", which is not public,";
    if (!field.getModifiers().contains(Modifier.PUBLIC) && !samePackage(field, pkg)) {
      return "the field " + fieldName + ", which is not public,";
    }
    for (final var type : types) {
      final var hidden = hiddenIn(type, pkg);
      if (hidden != null) return "the type " + hidden.getQualifiedName() + ", which is not public,";
    }
    return null;
  }

  /** The class {@code type} names, at any depth, that {@code pkg} cannot reach, or null. */
  private TypeElement hiddenIn(final TypeMirror type, final PackageElement pkg) {
    return switch (type.getKind()) {
      case DECLARED -> {
        final var element = (TypeElement) ((DeclaredType) type).asElement();
        if (!reaches(element, pkg)) yield element;
        for (final var argument : ((DeclaredType) type).getTypeArguments()) {
          final var hidden = hiddenIn(argument, pkg);
          if (hidden != null) yield hidden;
        }
        yield null;
      }
      case ARRAY -> hiddenIn(((ArrayType) type).getComponentType(), pkg);
      case WILDCARD -> {
        final var wildcard = (WildcardType) type;
        final var bound = wildcard.getExtendsBound() != null ? wildcard.getExtendsBound() : wildcard.getSuperBound();
        yield bound == null ? null : hiddenIn(bound, pkg);
      }
      default -> null;
    };
  }

  /** Whether code in {@code pkg} can name {@code type} and every class enclosing it. */
  private boolean reaches(final TypeElement type, final PackageElement pkg) {
    for (Element at = type; at instanceof TypeElement enclosing; at = enclosing.getEnclosingElement()) {
      if (enclosing.getModifiers().contains(Modifier.PRIVATE)) return false;
      if (!enclosing.getModifiers().contains(Modifier.PUBLIC) && !samePackage(enclosing, pkg)) return false;
    }
    return true;
  }

  private boolean samePackage(final Element element, final PackageElement pkg) {
    return env.getElementUtils().getPackageOf(element).equals(pkg);
  }

  /**
   * {@code type} as source text: each class by its canonical name, with its type arguments, and
   * without the type annotations {@code TypeMirror.toString} would carry along.
   */
  private static String render(final TypeMirror type) {
    return switch (type.getKind()) {
      case DECLARED -> {
        final var declared = (DeclaredType) type;
        final var name = ((TypeElement) declared.asElement()).getQualifiedName().toString();
        yield declared.getTypeArguments().isEmpty()
          ? name
          : name +
            declared
              .getTypeArguments()
              .stream()
              .map(MappingsInterfaces::render)
              .collect(Collectors.joining(", ", "<", ">"));
      }
      case ARRAY -> render(((ArrayType) type).getComponentType()) + "[]";
      case WILDCARD -> {
        final var wildcard = (WildcardType) type;
        if (wildcard.getExtendsBound() != null) yield "? extends " + render(wildcard.getExtendsBound());
        if (wildcard.getSuperBound() != null) yield "? super " + render(wildcard.getSuperBound());
        yield "?";
      }
      default -> type.toString();
    };
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
      if (method.takes().indexOf('<') >= 0) suppressed.add("unchecked");
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
