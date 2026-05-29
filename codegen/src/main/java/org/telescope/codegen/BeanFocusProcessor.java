package org.telescope.codegen;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

/**
 * Annotation processor for {@link org.telescope.annotations.BeanFocus} — the bean analog of {@link
 * FocusProcessor}. For each annotated POJO, emits a fluent typed path navigator: a sibling class
 * {@code <Pojo>Path<R>} plus one container-step class per collection-shaped property. Method bodies
 * use {@code Telescope.lens(Pojo::getX, rebuild)} where {@code rebuild} is a compile-time-built
 * expression (static {@code builder()} when present, else a no-arg constructor with {@code setX}
 * setters), so the runtime path is reflection-free.
 *
 * <p>Field injection is unavailable to generated code, so a POJO that exposes neither a builder nor
 * a no-arg constructor + setters is a compile error here (use the runtime {@code
 * Telescope.ofBean}).
 *
 * <p>Each scalar property emits a terminal {@code Telescope<R, T>} method; each property whose type
 * is itself {@code @BeanFocus}-annotated emits a {@code <Sub>Path<R>}-returning method; each
 * container property (List/Set/Iterable, Map values, Optional) emits a container step with the
 * matching {@code each} / {@code eachValue} / {@code whenPresent} method.
 *
 * <p>Guards: the annotated element must be a top-level {@code class} (records use {@code @Focus}),
 * it must have at least one readable property, and a usable rebuild strategy must exist.
 */
@SupportedAnnotationTypes("org.telescope.annotations.BeanFocus")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class BeanFocusProcessor extends AbstractTelescopeProcessor {

  private record Prop(String name, String getter, TypeMirror type) {}

  @Override
  public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
    final var anno = processingEnv.getElementUtils().getTypeElement("org.telescope.annotations.BeanFocus");
    if (anno == null) return false;
    for (final var element : roundEnv.getElementsAnnotatedWith(anno)) {
      if (element.getKind() != ElementKind.CLASS) {
        error(element, "@BeanFocus is only supported on classes (records use @Focus)");
        continue;
      }
      if (element.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
        error(element, "@BeanFocus is only supported on top-level classes");
        continue;
      }
      generate((TypeElement) element);
    }
    return true;
  }

  private void generate(final TypeElement pojo) {
    final var elements = processingEnv.getElementUtils();
    final var pkg = elements.getPackageOf(pojo).getQualifiedName().toString();
    final var pojoName = pojo.getSimpleName().toString();
    final var pathName = pojoName + "Path";
    final var qualifiedPath = pkg.isEmpty() ? pathName : pkg + "." + pathName;

    final var props = properties(pojo);
    if (props.isEmpty()) {
      error(pojo, "@BeanFocus: " + pojo.getQualifiedName() + " has no readable properties (getX()/isX())");
      return;
    }

    // Resolve the rebuild strategy once and the setter expression per property.
    final var builder = staticNoArgMethod(pojo, "builder");
    final var builderType =
      builder != null && builder.getReturnType().getKind() == TypeKind.DECLARED
        ? (TypeElement) ((DeclaredType) builder.getReturnType()).asElement()
        : null;
    final var useBuilder = builderType != null && hasBuild(builderType);
    if (!useBuilder && !hasPublicNoArgConstructor(pojo)) {
      error(
        pojo,
        "@BeanFocus: " +
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
          "@BeanFocus: no " +
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

    // First, emit a container step class per collection-shaped property.
    for (final var p : props) {
      final var shape = traversalKind(p.type());
      if (shape != null) emitStep(pojo, pojoName, pkg, p, shape);
    }

    // Then emit the main Path navigator.
    writeInstanceClass(
      qualifiedPath,
      pathName,
      "<R>",
      "Generated by telescope-codegen for @BeanFocus POJO " + pojoName + ".",
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
        for (var i = 0; i < props.size(); i++) {
          emitPropertyMethod(out, pojoName, props, setters, useBuilder, props.get(i));
        }
        emitTelescopeForwarders(out, "path", pojoName);
      }
    );
  }

  private void emitPropertyMethod(
    final PrintWriter out,
    final String pojoName,
    final List<Prop> props,
    final String[] setters,
    final boolean useBuilder,
    final Prop target
  ) {
    final var setter = rebuild(target, props, setters, useBuilder, pojoName);
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
    final var subFq = navigableType(target.type(), ElementKind.CLASS, "org.telescope.annotations.BeanFocus");
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
    final var typeStr = boxedType(target.type());
    out.println("  public Telescope<R, " + typeStr + "> " + target.name() + "() {");
    out.println("    return path.then(Telescope.lens(" + pojoName + "::" + target.getter() + ", " + setter + "));");
    out.println("  }");
    out.println();
  }

  private void emitStep(
    final TypeElement pojo,
    final String pojoName,
    final String pkg,
    final Prop prop,
    final TraversalShape shape
  ) {
    final var stepName = pojoName + capitalize(prop.name()) + "Step";
    final var qualifiedStep = pkg.isEmpty() ? stepName : pkg + "." + stepName;
    final var containerType = prop.type().toString();
    final var elementType = shape.elementType();
    final var stepMethod = shape.stepMethod();
    final var elementIsNavigable = isBeanFocusAnnotatedClass(elementType);
    final var elementResultType = elementIsNavigable ? elementType + "Path<R>" : "Telescope<R, " + elementType + ">";
    final var elementBody = elementIsNavigable
      ? "new " + elementType + "Path<>(path.<" + elementType + ">each())"
      : "path.<" + elementType + ">each()";

    writeInstanceClass(
      qualifiedStep,
      stepName,
      "<R>",
      "Generated by telescope-codegen for @BeanFocus container hop " + pojoName + "." + prop.name() + ".",
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
  private static String rebuild(
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

  private List<Prop> properties(final TypeElement pojo) {
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

  private boolean hasBuild(final TypeElement builderType) {
    for (final var m : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(builderType))) {
      if (isPublicInstance(m) && m.getParameters().isEmpty() && m.getSimpleName().contentEquals("build")) return true;
    }
    return false;
  }

  // Whether the type's qualified name names a class that itself carries @BeanFocus — i.e. has its
  // own generated *Path<R>. Used to decide a container step's element-result shape.
  private boolean isBeanFocusAnnotatedClass(final String qualifiedName) {
    final var elements = processingEnv.getElementUtils();
    final var element = elements.getTypeElement(qualifiedName);
    if (element == null || element.getKind() != ElementKind.CLASS) return false;
    final var anno = elements.getTypeElement("org.telescope.annotations.BeanFocus");
    if (anno == null) return false;
    for (final var am : element.getAnnotationMirrors()) {
      if (am.getAnnotationType().asElement().equals(anno)) return true;
    }
    return false;
  }
}
