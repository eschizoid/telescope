package io.github.eschizoid.telescope.codegen;

import io.github.eschizoid.telescope.internal.pairing.CollectionWrites;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * The adders of a bean as the {@code javax.lang.model} world sees them, by the rule the runtime's
 * {@code Beans.adders} applies to the loaded class, which {@link CollectionWrites} decides for
 * both: each collection property a field of its name stores is filled through the public instance
 * method of one parameter that the shared rule picks.
 */
final class MirrorAdders {

  private MirrorAdders() {}

  /**
   * The adder of each collection property of {@code bean} a field of its name stores, by property
   * name, and the refusal of the first that has none, null when every one has an adder.
   */
  record Adders(Map<String, ExecutableElement> bound, String refusal) {}

  /**
   * The adders of {@code bean}, whose properties are {@code properties}: each one's name and the
   * type it is declared as, as a member of {@code bean}.
   */
  static Adders of(
    final Elements elements,
    final Types types,
    final MirrorProps props,
    final TypeElement bean,
    final Map<String, TypeMirror> properties
  ) {
    final var writes = new CollectionWrites<>(props);
    final var bound = new LinkedHashMap<String, ExecutableElement>();
    String refusal = null;
    for (final var property : properties.entrySet()) {
      final var name = property.getKey();
      final var declared = property.getValue();
      if (!writes.filledByAdder(declared) || !storesAField(types, bean, name)) continue;
      final var element = writes.elementOf(declared);
      final ExecutableElement adder = writes.adderFor(
        name,
        element,
        candidate -> publicOneArgumentMethods(elements, bean, candidate),
        method -> parameterOf(types, bean, method)
      );
      if (adder != null) {
        bound.put(name, adder);
      } else if (refusal == null) {
        refusal = PairingMessages.adderMissing(
          bean.getSimpleName().toString(),
          name,
          element == null ? null : props.simpleName(element),
          CollectionWrites.adderNames(name)
        );
      }
    }
    return new Adders(Collections.unmodifiableMap(bound), refusal);
  }

  /** Whether {@code bean} or a superclass declares an instance field named {@code name}. */
  private static boolean storesAField(final Types types, final TypeElement bean, final String name) {
    for (var type = bean; type != null; ) {
      for (final var field : ElementFilter.fieldsIn(type.getEnclosedElements())) {
        if (field.getSimpleName().contentEquals(name) && !field.getModifiers().contains(Modifier.STATIC)) return true;
      }
      final var parent = type.getSuperclass();
      type = parent.getKind() == TypeKind.DECLARED ? (TypeElement) types.asElement(parent) : null;
      if (type != null && type.getQualifiedName().contentEquals("java.lang.Object")) type = null;
    }
    return false;
  }

  /** The public instance methods of {@code bean} named {@code name} that take one parameter. */
  private static List<ExecutableElement> publicOneArgumentMethods(
    final Elements elements,
    final TypeElement bean,
    final String name
  ) {
    final var found = new ArrayList<ExecutableElement>();
    for (final var method : ElementFilter.methodsIn(elements.getAllMembers(bean))) {
      if (
        method.getSimpleName().contentEquals(name) &&
        method.getParameters().size() == 1 &&
        method.getModifiers().contains(Modifier.PUBLIC) &&
        !method.getModifiers().contains(Modifier.STATIC)
      ) {
        found.add(method);
      }
    }
    return found;
  }

  /** The type {@code method}'s one parameter is declared as, as a member of {@code bean}. */
  private static TypeMirror parameterOf(final Types types, final TypeElement bean, final ExecutableElement method) {
    // A class's own type is always a declared type.
    return ((ExecutableType) types.asMemberOf((DeclaredType) bean.asType(), method)).getParameterTypes().getFirst();
  }
}
