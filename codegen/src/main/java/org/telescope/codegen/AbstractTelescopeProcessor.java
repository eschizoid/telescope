package org.telescope.codegen;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
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
 * BeanFocusProcessor}, {@link BridgeProcessor}): generated-source emission and the {@code
 * javax.lang.model} probes (setter/builder discovery, no-arg-constructor and static-factory checks,
 * name casing, primitive boxing) they all rely on. Each concrete processor keeps only its own
 * element discovery and the member body it writes.
 */
abstract class AbstractTelescopeProcessor extends AbstractProcessor {

  /**
   * Emit a generated {@code public final} class: the package declaration, the single {@code
   * org.telescope.Telescope} import, a one-line javadoc, and a private constructor, then {@code
   * body} writes the members, then the closing brace. The package is derived from {@code
   * qualifiedName}. An {@link IOException} is reported as a compile error on {@code origin}.
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
        out.println("import org.telescope.Telescope;");
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
  // members (getAllMembers), matching the runtime setters scan in org.telescope.internal.Beans.
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
   * Emit a non-utility generated class — i.e., one with instance state and a non-{@code private}
   * constructor. Header (package, single {@code org.telescope.Telescope} import, javadoc, class
   * declaration with {@code typeParams}) is written before {@code body}, then the closing brace.
   * The body is responsible for the class's constructor and members. IO failures are reported on
   * {@code origin}.
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
        out.println("import org.telescope.Telescope;");
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
}
