package io.github.eschizoid.telescope.codegen;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.util.ArrayDeque;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;

/**
 * What the bridge processor and the compile-time verifier both decide about a {@code compute} row's
 * method reference, so the two refuse the same rows in the same words as the runtime path.
 *
 * <p>Java binds a reference fitting both a supplier and a function to the {@code compute} overload
 * whose parameter extends both, as the supplier. Where the reference's name also has a form taking
 * the source, that form is the one the row never reaches. A reference bound to the {@code Function}
 * overload is called on the source, so the method must take it.
 */
final class ComputeForms {

  /**
   * The qualified name of the parameter type of the overload taking either a supplier or a
   * function.
   */
  static final String FRESH_FQN = "io.github.eschizoid.telescope.mapping.Mapping.Fresh";

  /** The qualified name of the parameter type of the overload taking a function of the source. */
  static final String FUNCTION_FQN = "java.util.function.Function";

  private ComputeForms() {}

  /**
   * The qualified name of the class parameter {@code index} of {@code method} erases to, or null.
   */
  static String parameterClass(final ExecutableElement method, final int index) {
    if (method.getParameters().size() <= index) return null;
    final var type = method.getParameters().get(index).asType();
    if (!(type instanceof DeclaredType declared)) return null;
    return ((TypeElement) declared.asElement()).getQualifiedName().toString();
  }

  /**
   * The refusal of a reference to {@code chosen}, which takes no argument, whose name has a form
   * taking {@code source} too, or null. The form is looked for as the runtime path looks: of the
   * same name and kind, static for a static method and an instance method for a bound one, or a
   * constructor of the same class, declared by the class declaring {@code chosen} or by a class or
   * interface it extends, in that order, not private, whose one parameter the source is an instance
   * of, a primitive parameter compared as its wrapper.
   */
  static String sourceFormRefusal(
    final Types types,
    final ExecutableElement chosen,
    final TypeElement source,
    final TypeElement target,
    final String property
  ) {
    if (!chosen.getParameters().isEmpty()) return null;
    final var sourceType = types.erasure(source.asType());
    final var owner = (TypeElement) chosen.getEnclosingElement();
    ExecutableElement form = null;
    if (chosen.getKind() == ElementKind.CONSTRUCTOR) {
      for (final var constructor : ElementFilter.constructorsIn(owner.getEnclosedElements())) {
        if (takesOne(types, constructor, sourceType)) {
          form = constructor;
          break;
        }
      }
    } else {
      final var wantStatic = chosen.getModifiers().contains(Modifier.STATIC);
      final var pending = new ArrayDeque<TypeElement>();
      pending.add(owner);
      while (form == null && !pending.isEmpty()) {
        final var type = pending.poll();
        for (final var method : ElementFilter.methodsIn(type.getEnclosedElements())) {
          if (
            method.getSimpleName().contentEquals(chosen.getSimpleName()) &&
            method.getModifiers().contains(Modifier.STATIC) == wantStatic &&
            takesOne(types, method, sourceType)
          ) {
            form = method;
            break;
          }
        }
        for (final var supertype : types.directSupertypes(type.asType())) {
          if (types.asElement(supertype) instanceof TypeElement parent) pending.add(parent);
        }
      }
    }
    if (form == null) return null;
    final var formOwner = form.getEnclosingElement().getSimpleName();
    final var parameter = shownName(types, types.erasure(form.getParameters().get(0).asType()));
    final var shownForm =
      form.getKind() == ElementKind.CONSTRUCTOR
        ? "new " + formOwner + "(" + parameter + ")"
        : formOwner + "." + form.getSimpleName() + "(" + parameter + ")";
    return PairingMessages.computeSupplierHasASourceForm(
      source.getSimpleName().toString(),
      target.getSimpleName().toString(),
      property,
      referenceName(form),
      shownForm
    );
  }

  /**
   * The refusal of a function reference to {@code method} that does not take {@code source}, or
   * null: a static method, a constructor or a method of a held receiver takes it as its one
   * parameter, and an unbound instance method is called on it, so its class must be the source's or
   * a type the source extends.
   */
  static String offSourceRefusal(
    final Types types,
    final ExecutableElement method,
    final TypeElement source,
    final TypeElement target,
    final String property
  ) {
    final var owner = (TypeElement) method.getEnclosingElement();
    final TypeMirror takes = method.getParameters().isEmpty() ? owner.asType() : method.getParameters().get(0).asType();
    if (types.isAssignable(types.erasure(source.asType()), types.erasure(takes))) return null;
    return PairingMessages.computeFunctionOffSource(
      source.getSimpleName().toString(),
      target.getSimpleName().toString(),
      property,
      referenceName(method),
      shownName(types, types.erasure(takes))
    );
  }

  /** A method or constructor as a message names it: {@code Names::full}, {@code Wrapped::new}. */
  static String referenceName(final ExecutableElement method) {
    final var owner = method.getEnclosingElement().getSimpleName();
    return owner + "::" + (method.getKind() == ElementKind.CONSTRUCTOR ? "new" : method.getSimpleName());
  }

  /** The simple name of the class {@code erased} names, or the type itself for a primitive. */
  static String shownName(final Types types, final TypeMirror erased) {
    final var element = types.asElement(erased);
    return element == null ? erased.toString() : element.getSimpleName().toString();
  }

  /** Whether {@code executable} is not private and takes one parameter {@code argument} fits. */
  private static boolean takesOne(final Types types, final ExecutableElement executable, final TypeMirror argument) {
    if (executable.getParameters().size() != 1 || executable.getModifiers().contains(Modifier.PRIVATE)) return false;
    final var parameter = executable.getParameters().get(0).asType();
    final var boxed = parameter.getKind().isPrimitive()
      ? types.boxedClass((PrimitiveType) parameter).asType()
      : types.erasure(parameter);
    return types.isAssignable(argument, boxed);
  }
}
