package io.github.eschizoid.telescope.codegen;

import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.util.ArrayDeque;
import java.util.List;
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
 * <p>Java binds a reference fitting both a supplier and a function of the source to the {@code
 * compute} overload whose parameter extends both, as the supplier. Where the reference's name also
 * has a form taking the source, that form is the one the row never reaches. A reference bound to
 * the overload taking a function of the source is called on the source, so the class it takes must
 * be the source's or a type the source extends.
 */
final class ComputeForms {

  /** The qualified name of the parameter type of the overload taking a supplier or a function. */
  static final String FRESH_FQN = "io.github.eschizoid.telescope.mapping.Mapping.Fresh";

  /** The qualified name of the parameter type of the overload taking a function of the source. */
  static final String FROM_SOURCE_FQN = "io.github.eschizoid.telescope.mapping.Mapping.FromSource";

  private ComputeForms() {}

  /**
   * The qualified name of the class the second parameter of {@code compute} overload {@code row}
   * names.
   */
  static String computeForm(final ExecutableElement row) {
    final var type = (DeclaredType) row.getParameters().get(1).asType();
    return ((TypeElement) type.asElement()).getQualifiedName().toString();
  }

  /**
   * The refusal of a reference to {@code chosen}, which takes no argument, whose name has a form
   * taking {@code source} too, or null. The form is looked for as the runtime path looks: of the
   * same name and kind, static for a static method and an instance method for a bound one, or a
   * constructor of the same class; not private; whose one parameter the source is an instance of, a
   * primitive parameter compared as its wrapper, and is not {@code Object}, which a type variable
   * with no bound erases to and which takes any value rather than the source. The class declaring
   * {@code chosen} is searched first, then each class and interface it extends, superclass before
   * interfaces, and the first that declares a form gives it; of several there, the one whose
   * parameter's class name sorts first.
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
    final var constructor = chosen.getKind() == ElementKind.CONSTRUCTOR;
    ExecutableElement form = null;
    if (constructor) {
      form = firstByParameter(
        types,
        ElementFilter.constructorsIn(owner.getEnclosedElements()),
        sourceType,
        null,
        false
      );
    } else {
      final var wantStatic = chosen.getModifiers().contains(Modifier.STATIC);
      final var pending = new ArrayDeque<TypeElement>();
      pending.add(owner);
      while (form == null && !pending.isEmpty()) {
        final var type = pending.poll();
        form = firstByParameter(
          types,
          ElementFilter.methodsIn(type.getEnclosedElements()),
          sourceType,
          chosen.getSimpleName().toString(),
          wantStatic
        );
        for (final var supertype : types.directSupertypes(type.asType())) {
          pending.add((TypeElement) types.asElement(supertype));
        }
      }
    }
    if (form == null) return null;
    final var formOwner = form.getEnclosingElement().getSimpleName();
    final var parameter = shownName(types, types.erasure(form.getParameters().get(0).asType()));
    return PairingMessages.computeSupplierHasASourceForm(
      source.getSimpleName().toString(),
      target.getSimpleName().toString(),
      property,
      owner.getSimpleName() + "::" + (constructor ? "new" : chosen.getSimpleName()),
      constructor
        ? "new " + formOwner + "(" + parameter + ")"
        : formOwner + "." + form.getSimpleName() + "(" + parameter + ")"
    );
  }

  /**
   * The refusal of a function of the source whose type at the call, {@code function}, takes a class
   * {@code source} is not, or null. The class taken is the functional interface's first type
   * argument as the call inferred it, which is what the runtime path reads from the function's
   * instantiation. {@code method} is the method or constructor a reference names, which {@code
   * unbound} says is called on the value it is handed, and null for a lambda.
   */
  static String offSourceRefusal(
    final Types types,
    final TypeMirror function,
    final ExecutableElement method,
    final boolean unbound,
    final TypeElement source,
    final TypeElement target,
    final String property
  ) {
    final var taken = takenBy(types, function);
    if (taken == null || types.isAssignable(types.erasure(source.asType()), taken)) return null;
    return PairingMessages.computeFunctionOffSource(
      source.getSimpleName().toString(),
      target.getSimpleName().toString(),
      property,
      method == null ? "a lambda" : referenceName(types, method, unbound, taken),
      shownName(types, taken)
    );
  }

  /**
   * The erased class a function whose type is {@code function} takes: the type's first type
   * argument, which the call's inference fixes; null where the type has none.
   */
  private static TypeMirror takenBy(final Types types, final TypeMirror function) {
    if (!(function instanceof DeclaredType declared) || declared.getTypeArguments().isEmpty()) return null;
    return types.erasure(declared.getTypeArguments().get(0));
  }

  /**
   * A function written as a reference to {@code method} as a message names it: an unbound instance
   * method by the class it is applied to, {@code taken}, and any other method or constructor by the
   * class declaring it ({@code Names::full}, {@code Wrapped::new}), as the runtime path names the
   * function from its {@code SerializedLambda}.
   */
  static String referenceName(
    final Types types,
    final ExecutableElement method,
    final boolean unbound,
    final TypeMirror taken
  ) {
    final var constructor = method.getKind() == ElementKind.CONSTRUCTOR;
    final var owner = unbound ? shownName(types, taken) : method.getEnclosingElement().getSimpleName().toString();
    return owner + "::" + (constructor ? "new" : method.getSimpleName());
  }

  /** The simple name of the class {@code erased} names. */
  static String shownName(final Types types, final TypeMirror erased) {
    return types.asElement(erased).getSimpleName().toString();
  }

  /**
   * Of {@code candidates} named {@code name}, or of any name when it is null, static exactly when
   * {@code wantStatic} for a method, the one not private whose one parameter, not {@code Object},
   * {@code argument} fits, whose parameter's class name sorts first; or null.
   */
  private static ExecutableElement firstByParameter(
    final Types types,
    final List<ExecutableElement> candidates,
    final TypeMirror argument,
    final String name,
    final boolean wantStatic
  ) {
    ExecutableElement first = null;
    String firstName = null;
    for (final var candidate : candidates) {
      final var named =
        name == null ||
        (candidate.getSimpleName().contentEquals(name) &&
          candidate.getModifiers().contains(Modifier.STATIC) == wantStatic);
      if (
        !named || candidate.getParameters().size() != 1 || candidate.getModifiers().contains(Modifier.PRIVATE)
      ) continue;
      final var parameter = candidate.getParameters().get(0).asType();
      final var boxed = parameter.getKind().isPrimitive()
        ? types.boxedClass((PrimitiveType) parameter).asType()
        : types.erasure(parameter);
      if (!(types.asElement(boxed) instanceof TypeElement parameterClass)) continue;
      final var sortName = parameterClass.getQualifiedName().toString();
      if (sortName.equals("java.lang.Object") || !types.isAssignable(argument, boxed)) continue;
      if (first == null || sortName.compareTo(firstName) < 0) {
        first = candidate;
        firstName = sortName;
      }
    }
    return first;
  }
}
