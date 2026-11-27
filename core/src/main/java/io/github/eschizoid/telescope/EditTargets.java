package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.ContainerCopy;
import io.github.eschizoid.telescope.internal.Beans;
import io.github.eschizoid.telescope.internal.Reflective;
import io.github.eschizoid.telescope.internal.pairing.GeneratedProperties;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The target properties of an edit mapper that no edit writes without a row saying so: those whose
 * persistence mapping generates their value, as the registered container policy answers, and the
 * back-reference of an element, which the write of the collection holding it sets.
 */
final class EditTargets {

  private EditTargets() {}

  /** The properties of {@code target} the registered container policy answers are generated. */
  static List<String> generated(final Class<?> target, final Reflective refl) {
    if (target.isRecord()) return List.of();
    final var names = new ArrayList<String>();
    for (final var name : refl.names(target)) if (ContainerCopy.generated(target, name)) names.add(name);
    return names;
  }

  /**
   * The generated properties of {@code target} that the conversion of a pair an edit mapper writes
   * never writes: every one but those a row writes, as {@code claimed} and {@code rowWritten} hold.
   */
  static List<String> unwritten(
    final Class<?> target,
    final Reflective refl,
    final Set<String> claimed,
    final Set<String> rowWritten
  ) {
    return GeneratedProperties.unwritten(
      generated(target, refl),
      name -> claimed.contains(name) || rowWritten.contains(name)
    );
  }

  /**
   * The properties of {@code target} that point back at an owner holding a collection of {@code
   * target}: the one a {@code backReference} row given to this mapper names for {@code target}, and
   * each property of a class whose collection of {@code target} names it in what the registered
   * container policy answers, as {@code @OneToMany(mappedBy)} does with {@code telescope-jpa}.
   */
  static List<String> backReferences(
    final Class<?> target,
    final Reflective refl,
    final DeepMap.BackReferenceRows rows
  ) {
    if (target.isRecord()) return List.of();
    final var names = new ArrayList<String>();
    final var pinned = rows.ownedBy(target);
    if (pinned != null) names.add(pinned);
    for (final var name : refl.names(target)) {
      if (names.contains(name)) continue;
      final var owner = rawClass(refl.genericType(target, name));
      if (
        owner == null ||
        owner.isRecord() ||
        owner.isEnum() ||
        owner.isPrimitive() ||
        owner.getName().startsWith("java.")
      ) continue;
      for (final var collection : Reflective.BEANS.names(owner)) {
        final var element = Beans.collectionElement(owner, collection);
        if (element == null || !element.isAssignableFrom(target)) continue;
        if (name.equals(ContainerCopy.backReference(owner, collection))) {
          names.add(name);
          break;
        }
      }
    }
    return names;
  }

  private static Class<?> rawClass(final Type type) {
    if (type instanceof Class<?> cls) return cls;
    if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
      return raw;
    }
    return null;
  }
}
