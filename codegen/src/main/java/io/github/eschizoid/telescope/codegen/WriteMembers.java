package io.github.eschizoid.telescope.codegen;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;

/**
 * Whether a bean has a member that writes a property, as the {@code javax.lang.model} world sees
 * it, by the rule the runtime's {@code Beans.noWriteMember} applies to the loaded class: a public
 * setter of the property's name, a member answering to it on the builder a public static {@code
 * builder()} returns, or a public constructor with a parameter of its name.
 */
final class WriteMembers {

  private WriteMembers() {}

  /**
   * Whether bean {@code type} has no member that writes {@code property}, which makes it a getter
   * the class derives from its other state. False for a record, whose canonical constructor writes
   * every component.
   */
  static boolean noWriteMember(final Elements elements, final TypeElement type, final String property) {
    if (type.getKind() == ElementKind.RECORD) return false;
    final var capital = property.isEmpty()
      ? property
      : Character.toUpperCase(property.charAt(0)) + property.substring(1);
    for (final var m : ElementFilter.methodsIn(elements.getAllMembers(type))) {
      if (publicInstance(m) && m.getParameters().size() == 1 && m.getSimpleName().contentEquals("set" + capital)) {
        return false;
      }
    }
    final var builderType = builderType(elements, type);
    if (builderType != null) {
      for (final var m : ElementFilter.methodsIn(elements.getAllMembers(builderType))) {
        if (!publicInstance(m) || m.getParameters().size() != 1) continue;
        final var name = m.getSimpleName().toString();
        if (name.equals(property) || name.equals("set" + capital) || name.equals("with" + capital)) return false;
      }
    }
    for (final var ctor : ElementFilter.constructorsIn(type.getEnclosedElements())) {
      if (!ctor.getModifiers().contains(Modifier.PUBLIC)) continue;
      for (final var parameter : ctor.getParameters()) {
        if (parameter.getSimpleName().contentEquals(property)) return false;
      }
    }
    return true;
  }

  /**
   * The builder a public static no-argument {@code builder()} returns, when it has a {@code
   * build()}.
   */
  private static TypeElement builderType(final Elements elements, final TypeElement type) {
    for (final var m : ElementFilter.methodsIn(elements.getAllMembers(type))) {
      if (
        !m.getModifiers().contains(Modifier.PUBLIC) ||
        !m.getModifiers().contains(Modifier.STATIC) ||
        !m.getParameters().isEmpty() ||
        !m.getSimpleName().contentEquals("builder") ||
        m.getReturnType().getKind() != TypeKind.DECLARED
      ) {
        continue;
      }
      final var builder = (TypeElement) ((DeclaredType) m.getReturnType()).asElement();
      for (final var b : ElementFilter.methodsIn(elements.getAllMembers(builder))) {
        if (publicInstance(b) && b.getParameters().isEmpty() && b.getSimpleName().contentEquals("build")) {
          return builder;
        }
      }
    }
    return null;
  }

  private static boolean publicInstance(final ExecutableElement m) {
    return m.getModifiers().contains(Modifier.PUBLIC) && !m.getModifiers().contains(Modifier.STATIC);
  }
}
