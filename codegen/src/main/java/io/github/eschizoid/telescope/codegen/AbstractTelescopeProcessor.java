package io.github.eschizoid.telescope.codegen;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.processing.AbstractProcessor;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

/**
 * Shared machinery for the telescope annotation processors ({@link FocusProcessor}, {@link
 * BeanFocusProcessor}, {@link BridgeProcessor}, and any out-of-tree extensions such as the {@code
 * telescope-lombok} module's {@code LombokFocusProcessor}): generated-source emission, the
 * bean-style navigator emit pipeline ({@link #emitBeanNavigator}), and the {@code javax.lang.model}
 * probes (setter/builder discovery, no-arg-constructor and static-factory checks, name casing,
 * primitive boxing) they all rely on. Each concrete processor keeps only its own element discovery;
 * the body it writes is shared here.
 *
 * <p>Public so an external module can subclass it and reuse {@link #emitBeanNavigator} without
 * needing to live in this package.
 */
public abstract class AbstractTelescopeProcessor extends AbstractProcessor {

  /** For subclasses; this type is not instantiated directly. */
  protected AbstractTelescopeProcessor() {
    super();
  }

  /**
   * A discovered bean-style property: its lowercase name (e.g. {@code email}), its public getter
   * (e.g. {@code getEmail} or {@code isActive}), and its declared return type. Used by {@link
   * #emitBeanNavigator} to drive both the per-property method emission and the rebuild expression
   * construction.
   */
  protected record Prop(String name, String getter, TypeMirror type) {}

  /**
   * Emit a generated {@code public final} class: the package declaration, the single {@code
   * io.github.eschizoid.telescope.Telescope} import, a one-line javadoc, and a private constructor,
   * then {@code body} writes the members, then the closing brace. The package is derived from
   * {@code qualifiedName}. An {@link IOException} is reported as a compile error on {@code origin}.
   */
  protected void writeClass(
    final String qualifiedName,
    final String simpleName,
    final String javadoc,
    final Element origin,
    final Consumer<PrintWriter> body
  ) {
    final var dot = qualifiedName.lastIndexOf('.');
    final var pkg = dot < 0 ? "" : qualifiedName.substring(0, dot);
    try {
      final var file = processingEnv.getFiler().createSourceFile(qualifiedName, origin);
      try (final var out = new PrintWriter(file.openWriter())) {
        if (!pkg.isEmpty()) {
          out.println("package " + pkg + ";");
          out.println();
        }
        out.println("import io.github.eschizoid.telescope.Telescope;");
        out.println();
        out.println("/** " + javadoc + " */");
        out.println("public final class " + simpleName + " {");
        out.println();
        out.println("  private " + simpleName + "() {}");
        out.println();
        body.accept(out);
        out.println("}");
      }
    } catch (final IOException e) {
      error(origin, "Failed to write " + qualifiedName + ": " + e.getMessage());
    }
  }

  protected void error(final Element element, final String message) {
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
  }

  // A public single-argument setter named setX for the property, or null. Considers inherited
  // members (getAllMembers), matching the runtime setters scan in
  // io.github.eschizoid.telescope.internal.Beans.
  protected String setterName(final TypeElement type, final String property) {
    final var setter = "set" + capitalize(property);
    for (final var m : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(type))) {
      if (isPublicInstance(m) && m.getParameters().size() == 1 && m.getSimpleName().contentEquals(setter)) {
        return setter;
      }
    }
    return null;
  }

  // A public single-argument builder method named property / setX / withX, or null.
  protected String builderSetter(final TypeElement builderType, final String property) {
    final var set = "set" + capitalize(property);
    final var with = "with" + capitalize(property);
    for (final var m : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(builderType))) {
      if (!isPublicInstance(m) || m.getParameters().size() != 1) continue;
      final var name = m.getSimpleName().toString();
      if (name.equals(property) || name.equals(set) || name.equals(with)) return name;
    }
    return null;
  }

  // A directly-declared public static no-arg method by name (e.g. builder()), or null.
  protected static ExecutableElement staticNoArgMethod(final TypeElement type, final String name) {
    for (final var m : ElementFilter.methodsIn(type.getEnclosedElements())) {
      if (
        m.getModifiers().contains(Modifier.PUBLIC) &&
        m.getModifiers().contains(Modifier.STATIC) &&
        m.getParameters().isEmpty() &&
        m.getSimpleName().contentEquals(name)
      ) {
        return m;
      }
    }
    return null;
  }

  protected static boolean hasPublicNoArgConstructor(final TypeElement type) {
    for (final var ctor : ElementFilter.constructorsIn(type.getEnclosedElements())) {
      if (ctor.getModifiers().contains(Modifier.PUBLIC) && ctor.getParameters().isEmpty()) return true;
    }
    return false;
  }

  protected static boolean isPublicInstance(final ExecutableElement m) {
    return m.getModifiers().contains(Modifier.PUBLIC) && !m.getModifiers().contains(Modifier.STATIC);
  }

  protected static String capitalize(final String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  protected static String decapitalize(final String s) {
    if (s.isEmpty()) return s;
    if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))) return s;
    return Character.toLowerCase(s.charAt(0)) + s.substring(1);
  }

  /**
   * Describes a container-shaped component: its concrete element type plus the runtime-DSL method
   * name the generated container step exposes ({@code each} / {@code eachValue} / {@code
   * whenPresent}). Returned by {@link #traversalKind}; {@code null} when the type isn't a
   * traversable container.
   */
  protected record TraversalShape(String elementType, String stepMethod) {}

  /**
   * The traversal shape of a collection-shaped {@code type}, or {@code null} if it isn't
   * traversable. {@code Map<K,V>} yields its <em>value</em> type with step method {@code eachValue}
   * (keys preserved); {@code Optional<E>} yields {@code E} with {@code whenPresent}; {@code
   * List}/{@code Set}/{@code Iterable<E>} yield {@code E} with {@code each}. Mirrors the runtime
   * {@code Telescope.each} / {@code eachValue} / {@code whenPresent} naming so the generated
   * navigator reads the same way as the reflective DSL.
   */
  protected TraversalShape traversalKind(final TypeMirror type) {
    if (type.getKind() != TypeKind.DECLARED) return null;
    final var declared = (DeclaredType) type;
    final var types = processingEnv.getTypeUtils();
    final var elements = processingEnv.getElementUtils();
    final var args = declared.getTypeArguments();
    final var erasure = types.erasure(declared);

    final var map = elements.getTypeElement("java.util.Map");
    if (map != null && types.isAssignable(erasure, types.erasure(map.asType()))) {
      final var elem = concreteArg(args, 1);
      return elem == null ? null : new TraversalShape(elem, "eachValue");
    }
    final var optional = elements.getTypeElement("java.util.Optional");
    if (optional != null && types.isSameType(erasure, types.erasure(optional.asType()))) {
      final var elem = concreteArg(args, 0);
      return elem == null ? null : new TraversalShape(elem, "whenPresent");
    }
    final var iterable = elements.getTypeElement("java.lang.Iterable");
    if (iterable != null && types.isAssignable(erasure, types.erasure(iterable.asType()))) {
      final var elem = concreteArg(args, 0);
      return elem == null ? null : new TraversalShape(elem, "each");
    }
    return null;
  }

  /**
   * The fully-qualified name of the {@code @Bridge} target on {@code source}, or {@code null} if
   * {@code source} doesn't carry an {@code @Bridge} annotation. Drives the "bridge hop" emission on
   * a Path navigator — if the type also carries {@code @Focus} or {@code @BeanFocus}, the navigator
   * gains an {@code as<Target>()} method that chains the generated {@code <Source>Bridge.BRIDGE}
   * constant.
   */
  protected String bridgeTargetFqn(final TypeElement source) {
    final var anno = processingEnv.getElementUtils().getTypeElement("io.github.eschizoid.telescope.annotations.Bridge");
    if (anno == null) return null;
    for (final var am : source.getAnnotationMirrors()) {
      if (!am.getAnnotationType().asElement().equals(anno)) continue;
      for (final var entry : am.getElementValues().entrySet()) {
        if (entry.getKey().getSimpleName().contentEquals("value")) {
          final var value = (TypeMirror) entry.getValue().getValue();
          if (value.getKind() == TypeKind.DECLARED) {
            return ((TypeElement) ((DeclaredType) value).asElement()).getQualifiedName().toString();
          }
        }
      }
    }
    return null;
  }

  /**
   * Whether the type named by {@code qualifiedName} is itself navigable — i.e. has a generated
   * {@code <X>Path<R>} via {@code @Focus} (on records), {@code @BeanFocus} (on classes), or one of
   * the Lombok bean annotations ({@code @lombok.Data} / {@code @lombok.Value} /
   * {@code @lombok.Builder}) when the {@code telescope-lombok} module is on the processor path.
   * Drives the bridge hop's return type: navigable target → {@code <Target>Path<R>}; otherwise
   * terminal {@code Telescope<R, Target>}. Lombok annotations are looked up by string FQN so this
   * module incurs no compile-time Lombok dependency.
   */
  protected boolean isNavigablePath(final String qualifiedName) {
    final var elements = processingEnv.getElementUtils();
    final var element = elements.getTypeElement(qualifiedName);
    if (element == null) return false;
    if (element.getKind() == ElementKind.RECORD) {
      return hasAnnotation(element, "io.github.eschizoid.telescope.annotations.Focus");
    }
    if (element.getKind() == ElementKind.CLASS) {
      if (hasAnnotation(element, "io.github.eschizoid.telescope.annotations.BeanFocus")) return true;
      for (final var fqn : LOMBOK_BEAN_ANNOTATIONS) {
        if (hasAnnotation(element, fqn)) return true;
      }
    }
    return false;
  }

  /**
   * Lombok annotations that the {@code telescope-lombok} processor treats as bean-style triggers.
   * Kept here as bare strings (looked up reflectively via {@link
   * javax.lang.model.util.Elements#getTypeElement}) so this module — and its consumers — never gain
   * a hard Lombok dependency. {@link #isNavigablePath} consults this set to recognise that a
   * Lombok-annotated sub-component has its own generated Path.
   */
  protected static final Set<String> LOMBOK_BEAN_ANNOTATIONS = Set.of("lombok.Data", "lombok.Value", "lombok.Builder");

  private boolean hasAnnotation(final Element element, final String annotationFqn) {
    final var anno = processingEnv.getElementUtils().getTypeElement(annotationFqn);
    if (anno == null) return false;
    for (final var am : element.getAnnotationMirrors()) {
      if (am.getAnnotationType().asElement().equals(anno)) return true;
    }
    return false;
  }

  /**
   * The simple name of a fully-qualified type — the part after the last {@code .} (or the whole
   * name).
   */
  protected static String simpleNameOf(final String qualifiedName) {
    final var dot = qualifiedName.lastIndexOf('.');
    return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
  }

  /**
   * Emit a bridge hop method on a Path navigator: {@code as<TargetSimpleName>()} that chains the
   * generated {@code <SourceSimpleName>Bridge.BRIDGE} constant onto the current path. The return
   * type is the target's Path when navigable, else a terminal Telescope. Bridge constant is
   * referenced by simple name because {@code @Bridge} always emits the bridge class in the source's
   * package — the same package as the navigator.
   */
  protected void emitBridgeHop(final PrintWriter out, final String sourceSimpleName, final String targetFqn) {
    final var targetSimple = simpleNameOf(targetFqn);
    final var bridgeName = sourceSimpleName + "Bridge";
    final var methodName = "as" + targetSimple;
    if (isNavigablePath(targetFqn)) {
      out.println("  public " + targetFqn + "Path<R> " + methodName + "() {");
      out.println("    return new " + targetFqn + "Path<>(path.then(" + bridgeName + ".BRIDGE));");
      out.println("  }");
    } else {
      out.println("  public Telescope<R, " + targetFqn + "> " + methodName + "() {");
      out.println("    return path.then(" + bridgeName + ".BRIDGE);");
      out.println("  }");
    }
    out.println();
  }

  /**
   * The fully-qualified name of {@code type}'s element if it's a top-level type carrying the
   * annotation named by {@code annotationFqn} and of the given {@code requiredKind} (typically
   * {@link ElementKind#RECORD} for {@code @Focus} or {@link ElementKind#CLASS} for
   * {@code @BeanFocus}); otherwise {@code null}. Drives the navigator's "descend into a sub-Path"
   * emission: only types that have their own generated {@code <Sub>Path<R>} are routed there;
   * everything else becomes a terminal {@code Telescope<R, T>}.
   */
  protected String navigableType(final TypeMirror type, final ElementKind requiredKind, final String annotationFqn) {
    if (type.getKind() != TypeKind.DECLARED) return null;
    final var element = ((DeclaredType) type).asElement();
    if (element.getKind() != requiredKind) return null;
    final var anno = processingEnv.getElementUtils().getTypeElement(annotationFqn);
    if (anno == null) return null;
    for (final var am : element.getAnnotationMirrors()) {
      if (am.getAnnotationType().asElement().equals(anno)) {
        return ((TypeElement) element).getQualifiedName().toString();
      }
    }
    return null;
  }

  /**
   * Emit forwarding methods for every public {@link io.github.eschizoid.telescope.Telescope}
   * operation on the wrapped path field {@code pathField} whose focus type is {@code focusType}.
   * Lets a generated {@code <X>Path<R>} or {@code <X><Cap>Step<R>} stand in for the wrapped {@code
   * Telescope<R, focusType>}: callers can do {@code update} / {@code updateAsync} / {@code read} /
   * {@code toList} / etc. (including the four effect methods and {@code then}) at any hop without
   * first unwrapping with {@code get()}.
   */
  protected void emitTelescopeForwarders(final PrintWriter out, final String pathField, final String focusType) {
    // Sync reads.
    out.println("  public " + focusType + " read(final R source) { return " + pathField + ".read(source); }");
    out.println();
    out.println("  public Optional<" + focusType + "> find(final R source) { return " + pathField + ".find(source); }");
    out.println();
    out.println("  public List<" + focusType + "> toList(final R source) { return " + pathField + ".toList(source); }");
    out.println();
    out.println(
      "  public List<Indexed<" +
        focusType +
        ">> toListIndexed(final R source) { return " +
        pathField +
        ".toListIndexed(source); }"
    );
    out.println();
    out.println("  public long count(final R source) { return " + pathField + ".count(source); }");
    out.println();
    out.println("  public boolean exists(final R source) { return " + pathField + ".exists(source); }");
    out.println();
    // Sync writes.
    out.println(
      "  public R set(final R source, final " + focusType + " value) { return " + pathField + ".set(source, value); }"
    );
    out.println();
    out.println(
      "  public R update(final R source, final Function<" +
        focusType +
        ", " +
        focusType +
        "> fn) { return " +
        pathField +
        ".update(source, fn); }"
    );
    out.println();
    out.println(
      "  public R updateIndexed(final R source, final BiFunction<Integer, ? super " +
        focusType +
        ", ? extends " +
        focusType +
        "> fn) { return " +
        pathField +
        ".updateIndexed(source, fn); }"
    );
    out.println();
    // Effectful writes.
    out.println(
      "  public CompletableFuture<R> updateAsync(final R source, final Function<? super " +
        focusType +
        ", ? extends CompletableFuture<" +
        focusType +
        ">> fn) { return " +
        pathField +
        ".updateAsync(source, fn); }"
    );
    out.println();
    out.println(
      "  public CompletableFuture<R> updateAsync(final R source, final Function<? super " +
        focusType +
        ", ? extends CompletableFuture<" +
        focusType +
        ">> fn, final Executor executor) { return " +
        pathField +
        ".updateAsync(source, fn, executor); }"
    );
    out.println();
    out.println(
      "  public Optional<R> updateOptional(final R source, final Function<? super " +
        focusType +
        ", ? extends Optional<" +
        focusType +
        ">> fn) { return " +
        pathField +
        ".updateOptional(source, fn); }"
    );
    out.println();
    out.println(
      "  public <E> Either<E, R> updateEither(final R source, final Function<? super " +
        focusType +
        ", ? extends Either<E, " +
        focusType +
        ">> fn) { return " +
        pathField +
        ".updateEither(source, fn); }"
    );
    out.println();
    out.println(
      "  public <E> Validated<E, R> updateValidated(final R source, final Function<? super " +
        focusType +
        ", ? extends Validated<E, " +
        focusType +
        ">> fn) { return " +
        pathField +
        ".updateValidated(source, fn); }"
    );
    out.println();
    // Composition with an external Telescope.
    out.println(
      "  public <B> Telescope<R, B> then(final Telescope<" +
        focusType +
        ", B> next) { return " +
        pathField +
        ".then(next); }"
    );
    out.println();
  }

  /**
   * Shorten well-known FQNs to their imported short forms, assuming the standard import block
   * emitted by {@link #writeInstanceClass} is present in the generated file. Cross-package types
   * (the user's own records / POJOs / etc.) stay fully-qualified — only the auto-imported and
   * always-imported standard names are collapsed.
   */
  protected static String shortenStdImports(final String typeName) {
    if (typeName == null) return null;
    return typeName
      .replace("java.util.concurrent.CompletableFuture", "CompletableFuture")
      .replace("java.util.concurrent.Executor", "Executor")
      .replace("java.util.function.BiFunction", "BiFunction")
      .replace("java.util.function.Function", "Function")
      .replace("java.util.Optional", "Optional")
      .replace("java.util.List", "List")
      .replace("java.util.Map", "Map")
      .replace("java.util.Set", "Set")
      .replace("io.github.eschizoid.telescope.Either", "Either")
      .replace("io.github.eschizoid.telescope.Validated", "Validated")
      .replace("io.github.eschizoid.telescope.Indexed", "Indexed")
      .replace("java.lang.String", "String")
      .replace("java.lang.Integer", "Integer")
      .replace("java.lang.Long", "Long")
      .replace("java.lang.Double", "Double")
      .replace("java.lang.Float", "Float")
      .replace("java.lang.Boolean", "Boolean")
      .replace("java.lang.Character", "Character")
      .replace("java.lang.Byte", "Byte")
      .replace("java.lang.Short", "Short")
      .replace("java.lang.Object", "Object");
  }

  /**
   * Emit a non-utility generated class — i.e., one with instance state and a non-{@code private}
   * constructor. Header (package, single {@code io.github.eschizoid.telescope.Telescope} import,
   * javadoc, class declaration with {@code typeParams}) is written before {@code body}, then the
   * closing brace. The body is responsible for the class's constructor and members. IO failures are
   * reported on {@code origin}.
   */
  protected void writeInstanceClass(
    final String qualifiedName,
    final String simpleName,
    final String typeParams,
    final String javadoc,
    final Element origin,
    final Consumer<PrintWriter> body
  ) {
    final var dot = qualifiedName.lastIndexOf('.');
    final var pkg = dot < 0 ? "" : qualifiedName.substring(0, dot);
    try {
      final var file = processingEnv.getFiler().createSourceFile(qualifiedName, origin);
      try (final var out = new PrintWriter(file.openWriter())) {
        if (!pkg.isEmpty()) {
          out.println("package " + pkg + ";");
          out.println();
        }
        out.println("import java.util.List;");
        out.println("import java.util.Map;");
        out.println("import java.util.Optional;");
        out.println("import java.util.concurrent.CompletableFuture;");
        out.println("import java.util.concurrent.Executor;");
        out.println("import java.util.function.BiFunction;");
        out.println("import java.util.function.Function;");
        out.println("import io.github.eschizoid.telescope.Either;");
        out.println("import io.github.eschizoid.telescope.Indexed;");
        out.println("import io.github.eschizoid.telescope.Telescope;");
        out.println("import io.github.eschizoid.telescope.Validated;");
        out.println();
        out.println("/** " + javadoc + " */");
        out.println("public final class " + simpleName + typeParams + " {");
        out.println();
        body.accept(out);
        out.println("}");
      }
    } catch (final IOException e) {
      error(origin, "Failed to write " + qualifiedName + ": " + e.getMessage());
    }
  }

  /**
   * @deprecated Superseded by {@link #traversalKind}, which also reports the step method name.
   *     Retained briefly during the navigator migration; remove when no caller remains.
   */
  @Deprecated
  protected String traversalElement(final TypeMirror type) {
    if (type.getKind() != TypeKind.DECLARED) return null;
    final var declared = (DeclaredType) type;
    final var types = processingEnv.getTypeUtils();
    final var elements = processingEnv.getElementUtils();
    final var args = declared.getTypeArguments();
    final var erasure = types.erasure(declared);

    final var map = elements.getTypeElement("java.util.Map");
    if (map != null && types.isAssignable(erasure, types.erasure(map.asType()))) {
      return concreteArg(args, 1); // Map<K,V> -> V (values; keys preserved)
    }
    final var optional = elements.getTypeElement("java.util.Optional");
    if (optional != null && types.isSameType(erasure, types.erasure(optional.asType()))) {
      return concreteArg(args, 0); // Optional<E> -> E
    }
    final var iterable = elements.getTypeElement("java.lang.Iterable");
    if (iterable != null && types.isAssignable(erasure, types.erasure(iterable.asType()))) {
      return concreteArg(args, 0); // List / Set / Iterable<E> -> E
    }
    return null;
  }

  // The type argument at {@code index} as a fully-qualified name, or null if absent or not a
  // concrete (declared) type — so raw types and wildcards fall back to the runtime path.
  private static String concreteArg(final List<? extends TypeMirror> args, final int index) {
    if (args.size() <= index) return null;
    final var arg = args.get(index);
    return arg.getKind() == TypeKind.DECLARED ? arg.toString() : null;
  }

  // The Telescope type parameter must be a reference type; box primitive types to their wrappers.
  protected static String boxedType(final TypeMirror type) {
    return switch (type.getKind()) {
      case BOOLEAN -> "Boolean";
      case BYTE -> "Byte";
      case SHORT -> "Short";
      case INT -> "Integer";
      case LONG -> "Long";
      case CHAR -> "Character";
      case FLOAT -> "Float";
      case DOUBLE -> "Double";
      default -> type.toString();
    };
  }

  /**
   * Emit the full bean-style {@code <X>Path<R>} navigator plus one container step class per
   * collection-shaped property. Shared between {@link BeanFocusProcessor} (driven by
   * {@code @BeanFocus}) and the out-of-tree {@code LombokFocusProcessor} (driven by
   * {@code @lombok.Data} / {@code @lombok.Value} / {@code @lombok.Builder}).
   *
   * @param pojo the annotated POJO to emit a navigator for
   * @param triggerLabel display name of the triggering annotation, used in error messages (e.g.
   *     {@code "@BeanFocus"} or {@code "@Data/@Value/@Builder"})
   * @param navigableAnnotations annotation FQNs that mark a class as having its own generated Path,
   *     so that sub-component navigation descends into {@code <Sub>Path<R>} rather than terminating
   *     in {@code Telescope<R, Sub>}
   */
  protected void emitBeanNavigator(
    final TypeElement pojo,
    final String triggerLabel,
    final Set<String> navigableAnnotations
  ) {
    final var elements = processingEnv.getElementUtils();
    final var pkg = elements.getPackageOf(pojo).getQualifiedName().toString();
    final var pojoName = pojo.getSimpleName().toString();
    final var pathName = pojoName + "Path";
    final var qualifiedPath = pkg.isEmpty() ? pathName : pkg + "." + pathName;

    final var props = beanProperties(pojo);
    if (props.isEmpty()) {
      error(pojo, triggerLabel + ": " + pojo.getQualifiedName() + " has no readable properties (getX()/isX())");
      return;
    }

    final var builder = staticNoArgMethod(pojo, "builder");
    final var builderType =
      builder != null && builder.getReturnType().getKind() == TypeKind.DECLARED
        ? (TypeElement) ((DeclaredType) builder.getReturnType()).asElement()
        : null;
    final var useBuilder = builderType != null && hasBuildMethod(builderType);
    if (!useBuilder && !hasPublicNoArgConstructor(pojo)) {
      error(
        pojo,
        triggerLabel +
          ": " +
          pojo.getQualifiedName() +
          " needs a static builder() or a public no-arg constructor with setters (field injection " +
          "isn't available to generated code — use Telescope.ofBean for the runtime path)"
      );
      return;
    }

    final var setters = new String[props.size()];
    for (var i = 0; i < props.size(); i++) {
      final var s = useBuilder
        ? builderSetter(builderType, props.get(i).name())
        : setterName(pojo, props.get(i).name());
      if (s == null) {
        error(
          pojo,
          triggerLabel +
            ": no " +
            (useBuilder ? "builder method" : "setter") +
            " for property '" +
            props.get(i).name() +
            "' on " +
            (useBuilder ? builderType.getQualifiedName() : pojo.getQualifiedName())
        );
        return;
      }
      setters[i] = s;
    }

    for (final var p : props) {
      final var shape = traversalKind(p.type());
      if (shape != null) emitBeanStep(pojo, pojoName, pkg, p, shape, navigableAnnotations);
    }

    writeInstanceClass(
      qualifiedPath,
      pathName,
      "<R>",
      "Generated by telescope-codegen for " + triggerLabel + " POJO " + pojoName + ".",
      pojo,
      out -> {
        out.println("  private final Telescope<R, " + pojoName + "> path;");
        out.println();
        out.println("  " + pathName + "(final Telescope<R, " + pojoName + "> path) { this.path = path; }");
        out.println();
        out.println(
          "  public static " +
            pathName +
            "<" +
            pojoName +
            "> start() { return new " +
            pathName +
            "<>(Telescope.of(" +
            pojoName +
            ".class)); }"
        );
        out.println();
        out.println("  public Telescope<R, " + pojoName + "> get() { return path; }");
        out.println();
        for (final var p : props) {
          emitBeanPropertyMethod(out, pojoName, props, setters, useBuilder, p, navigableAnnotations);
        }
        final var bridgeTarget = bridgeTargetFqn(pojo);
        if (bridgeTarget != null) emitBridgeHop(out, pojoName, bridgeTarget);
        emitTelescopeForwarders(out, "path", pojoName);
      }
    );
  }

  private void emitBeanPropertyMethod(
    final PrintWriter out,
    final String pojoName,
    final List<Prop> props,
    final String[] setters,
    final boolean useBuilder,
    final Prop target,
    final Set<String> navigableAnnotations
  ) {
    final var setter = beanRebuild(target, props, setters, useBuilder, pojoName);
    final var shape = traversalKind(target.type());
    if (shape != null) {
      final var stepName = pojoName + capitalize(target.name()) + "Step";
      out.println("  public " + stepName + "<R> " + target.name() + "() {");
      out.println(
        "    return new " +
          stepName +
          "<>(path.then(Telescope.lens(" +
          pojoName +
          "::" +
          target.getter() +
          ", " +
          setter +
          ")));"
      );
      out.println("  }");
      out.println();
      return;
    }
    final var subFq = navigableType(target.type(), navigableAnnotations);
    if (subFq != null) {
      out.println("  public " + subFq + "Path<R> " + target.name() + "() {");
      out.println(
        "    return new " +
          subFq +
          "Path<>(path.then(Telescope.lens(" +
          pojoName +
          "::" +
          target.getter() +
          ", " +
          setter +
          ")));"
      );
      out.println("  }");
      out.println();
      return;
    }
    final var typeStr = shortenStdImports(boxedType(target.type()));
    out.println("  public Telescope<R, " + typeStr + "> " + target.name() + "() {");
    out.println("    return path.then(Telescope.lens(" + pojoName + "::" + target.getter() + ", " + setter + "));");
    out.println("  }");
    out.println();
  }

  private void emitBeanStep(
    final TypeElement pojo,
    final String pojoName,
    final String pkg,
    final Prop prop,
    final TraversalShape shape,
    final Set<String> navigableAnnotations
  ) {
    final var stepName = pojoName + capitalize(prop.name()) + "Step";
    final var qualifiedStep = pkg.isEmpty() ? stepName : pkg + "." + stepName;
    final var containerType = shortenStdImports(prop.type().toString());
    final var rawElementType = shape.elementType();
    final var elementType = shortenStdImports(rawElementType);
    final var stepMethod = shape.stepMethod();
    final var elementIsNavigable = isAnnotatedClass(rawElementType, navigableAnnotations);
    final var elementResultType = elementIsNavigable ? elementType + "Path<R>" : "Telescope<R, " + elementType + ">";
    final var elementBody = elementIsNavigable
      ? "new " + elementType + "Path<>(path.<" + elementType + ">each())"
      : "path.<" + elementType + ">each()";

    writeInstanceClass(
      qualifiedStep,
      stepName,
      "<R>",
      "Generated by telescope-codegen container hop " + pojoName + "." + prop.name() + ".",
      pojo,
      out -> {
        out.println("  private final Telescope<R, " + containerType + "> path;");
        out.println();
        out.println("  " + stepName + "(final Telescope<R, " + containerType + "> path) { this.path = path; }");
        out.println();
        out.println("  public Telescope<R, " + containerType + "> get() { return path; }");
        out.println();
        out.println("  public " + elementResultType + " " + stepMethod + "() {");
        out.println("    return " + elementBody + ";");
        out.println("  }");
        out.println();
        emitTelescopeForwarders(out, "path", containerType);
      }
    );
  }

  // The rebuild expression for property `target`: builder chain or no-arg ctor + per-property
  // setters, with every other property read from `p` and `target` set to `v`.
  private static String beanRebuild(
    final Prop target,
    final List<Prop> all,
    final String[] setters,
    final boolean useBuilder,
    final String pojoName
  ) {
    if (useBuilder) {
      final var sb = new StringBuilder("(p, v) -> " + pojoName + ".builder()");
      for (var i = 0; i < all.size(); i++) {
        final var arg = all.get(i).name().equals(target.name()) ? "v" : "p." + all.get(i).getter() + "()";
        sb.append(".").append(setters[i]).append("(").append(arg).append(")");
      }
      return sb.append(".build()").toString();
    }
    final var sb = new StringBuilder("(p, v) -> { final var c = new " + pojoName + "(); ");
    for (var i = 0; i < all.size(); i++) {
      final var arg = all.get(i).name().equals(target.name()) ? "v" : "p." + all.get(i).getter() + "()";
      sb.append("c.").append(setters[i]).append("(").append(arg).append("); ");
    }
    return sb.append("return c; }").toString();
  }

  /**
   * Discover the bean-style readable properties of {@code pojo}: every public, no-arg, non-void
   * instance method named {@code getX} or {@code isX} (the latter only for {@code boolean} / {@code
   * Boolean} returns). Walks {@code getAllMembers}, so inherited and Lombok-synthesised getters are
   * both picked up. The reserved property {@code class} (from {@link Object#getClass}) is filtered
   * out. Insertion order is preserved.
   */
  protected List<Prop> beanProperties(final TypeElement pojo) {
    final Map<String, Prop> byName = new LinkedHashMap<>();
    for (final var m : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(pojo))) {
      if (!isPublicInstance(m) || !m.getParameters().isEmpty() || m.getReturnType().getKind() == TypeKind.VOID) {
        continue;
      }
      final var name = m.getSimpleName().toString();
      final String prop;
      if (name.length() > 3 && name.startsWith("get")) {
        prop = decapitalize(name.substring(3));
      } else if (
        name.length() > 2 &&
        name.startsWith("is") &&
        (m.getReturnType().getKind() == TypeKind.BOOLEAN || "java.lang.Boolean".equals(m.getReturnType().toString()))
      ) {
        prop = decapitalize(name.substring(2));
      } else {
        continue;
      }
      if (!"class".equals(prop)) byName.putIfAbsent(prop, new Prop(prop, name, m.getReturnType()));
    }
    return new ArrayList<>(byName.values());
  }

  /** Whether {@code builderType} exposes a public no-arg {@code build()} method. */
  protected boolean hasBuildMethod(final TypeElement builderType) {
    for (final var m : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(builderType))) {
      if (isPublicInstance(m) && m.getParameters().isEmpty() && m.getSimpleName().contentEquals("build")) return true;
    }
    return false;
  }

  /**
   * Whether the class named by {@code qualifiedName} is annotated with any of {@code
   * annotationFqns}. Used to decide a container step's element-result shape: a List/Set/Map element
   * type whose class carries a navigable annotation gets routed into its own {@code
   * <Element>Path<R>} rather than terminating in {@code Telescope<R, Element>}.
   */
  protected boolean isAnnotatedClass(final String qualifiedName, final Set<String> annotationFqns) {
    final var elements = processingEnv.getElementUtils();
    final var element = elements.getTypeElement(qualifiedName);
    if (element == null || element.getKind() != ElementKind.CLASS) return false;
    for (final var fqn : annotationFqns) {
      final var anno = elements.getTypeElement(fqn);
      if (anno == null) continue;
      for (final var am : element.getAnnotationMirrors()) {
        if (am.getAnnotationType().asElement().equals(anno)) return true;
      }
    }
    return false;
  }

  /**
   * Overload of {@link #navigableType(TypeMirror, ElementKind, String)} that returns the first type
   * whose element carries any of {@code annotationFqns}. Used by {@link #emitBeanNavigator} to
   * thread the per-processor set of "this class has its own Path" annotations through sub-component
   * navigation.
   */
  protected String navigableType(final TypeMirror type, final Set<String> annotationFqns) {
    for (final var fqn : annotationFqns) {
      final var found = navigableType(type, ElementKind.CLASS, fqn);
      if (found != null) return found;
    }
    return null;
  }
}
