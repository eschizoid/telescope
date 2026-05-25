package org.telescope.codegen;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
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
 * Annotation processor for {@link org.telescope.annotations.BeanFocus} — the bean analog of {@code
 * FocusProcessor}. For each annotated POJO it emits a sibling {@code <Pojo>Focus} class with a
 * {@code public static final Telescope<Pojo, PropType>} constant per readable property, each built
 * from {@code Telescope.lens(Pojo::getX, rebuild)} using only public members (no runtime
 * reflection).
 *
 * <p>The lens setter rebuilds the POJO with one property changed via a strategy auto-detected at
 * compile time, in priority order: a static {@code builder()} (with a per-property method {@code x}
 * / {@code setX} / {@code withX} and {@code build()}), then a public no-arg constructor plus {@code
 * setX} setters. Field injection is unavailable to generated code, so a POJO exposing neither is a
 * compile error (use {@code @BeanBridge} with a record, or runtime {@code Telescope.ofBean}).
 *
 * <p>Guards (each a compile error on the offending element): the annotated element must be a
 * top-level {@code class} (records use {@code @Focus}); it must have at least one readable
 * property; and a usable rebuild strategy must exist.
 */
@SupportedAnnotationTypes("org.telescope.annotations.BeanFocus")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class BeanFocusProcessor extends AbstractProcessor {

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
    final var focusName = pojoName + "Focus";
    final var qualifiedFocus = pkg.isEmpty() ? focusName : pkg + "." + focusName;

    final var props = properties(pojo);
    if (props.isEmpty()) {
      error(pojo, "@BeanFocus: " + pojo.getQualifiedName() + " has no readable properties (getX()/isX())");
      return;
    }

    // Resolve the rebuild strategy once, then a setter expression per property.
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
          "isn't available to generated code — use @BeanBridge with a record, or Telescope.ofBean)"
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

    try {
      final var file = processingEnv.getFiler().createSourceFile(qualifiedFocus, pojo);
      try (final var out = new PrintWriter(file.openWriter())) {
        if (!pkg.isEmpty()) {
          out.println("package " + pkg + ";");
          out.println();
        }
        out.println("import org.telescope.Telescope;");
        out.println();
        out.println("/** Generated by telescope-codegen for @BeanFocus POJO " + pojoName + ". */");
        out.println("public final class " + focusName + " {");
        out.println();
        out.println("  private " + focusName + "() {}");
        out.println();
        for (var i = 0; i < props.size(); i++) {
          final var p = props.get(i);
          out.println(
            "  public static final Telescope<" + pojoName + ", " + boxedType(p.type()) + "> " + p.name() + " ="
          );
          out.println(
            "    Telescope.lens(" +
              pojoName +
              "::" +
              p.getter() +
              ", " +
              rebuild(p, props, setters, useBuilder, pojoName) +
              ");"
          );
          out.println();
        }
        out.println("}");
      }
    } catch (final IOException e) {
      error(pojo, "Failed to write " + qualifiedFocus + ": " + e.getMessage());
    }
  }

  // The lens setter: rebuild the POJO with property `target` set to v, all others read from p.
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

  private String setterName(final TypeElement pojo, final String prop) {
    final var setter = "set" + capitalize(prop);
    for (final var m : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(pojo))) {
      if (isPublicInstance(m) && m.getParameters().size() == 1 && m.getSimpleName().contentEquals(setter)) {
        return setter;
      }
    }
    return null;
  }

  private String builderSetter(final TypeElement builderType, final String prop) {
    final var set = "set" + capitalize(prop);
    final var with = "with" + capitalize(prop);
    for (final var m : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(builderType))) {
      if (!isPublicInstance(m) || m.getParameters().size() != 1) continue;
      final var name = m.getSimpleName().toString();
      if (name.equals(prop) || name.equals(set) || name.equals(with)) return name;
    }
    return null;
  }

  private ExecutableElement staticNoArgMethod(final TypeElement pojo, final String name) {
    for (final var m : ElementFilter.methodsIn(pojo.getEnclosedElements())) {
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

  private boolean hasBuild(final TypeElement builderType) {
    for (final var m : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(builderType))) {
      if (isPublicInstance(m) && m.getParameters().isEmpty() && m.getSimpleName().contentEquals("build")) return true;
    }
    return false;
  }

  private static boolean hasPublicNoArgConstructor(final TypeElement pojo) {
    for (final var ctor : ElementFilter.constructorsIn(pojo.getEnclosedElements())) {
      if (ctor.getModifiers().contains(Modifier.PUBLIC) && ctor.getParameters().isEmpty()) return true;
    }
    return false;
  }

  private static boolean isPublicInstance(final ExecutableElement m) {
    return m.getModifiers().contains(Modifier.PUBLIC) && !m.getModifiers().contains(Modifier.STATIC);
  }

  private static String decapitalize(final String s) {
    if (s.isEmpty()) return s;
    if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))) return s;
    return Character.toLowerCase(s.charAt(0)) + s.substring(1);
  }

  private static String capitalize(final String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  // The Telescope type parameter must be a reference type; box primitive property types.
  private static String boxedType(final TypeMirror type) {
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

  private void error(final javax.lang.model.element.Element element, final String message) {
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
  }
}
