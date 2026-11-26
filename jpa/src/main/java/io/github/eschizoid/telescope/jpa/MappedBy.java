package io.github.eschizoid.telescope.jpa;

import io.github.eschizoid.telescope.internal.Beans;
import jakarta.persistence.OneToMany;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * The back-reference {@code @OneToMany(mappedBy = ...)} names for each collection of an entity: the
 * element's property that points back at the owner of a bidirectional association. The annotation
 * is read where JPA reads it, on the field of the collection's name or on its getter, the first
 * found walking from the class up its superclasses. A collection with no such annotation, or one
 * whose {@code mappedBy} is empty, names none.
 */
final class MappedBy {

  private static final ClassValue<Map<String, String>> OF = new ClassValue<>() {
    @Override
    protected Map<String, String> computeValue(final Class<?> type) {
      return discover(type);
    }
  };

  private MappedBy() {}

  /**
   * The property {@code @OneToMany(mappedBy)} names for collection {@code property} of {@code
   * owner}, or null.
   */
  static String of(final Class<?> owner, final String property) {
    return OF.get(owner).get(property);
  }

  private static Map<String, String> discover(final Class<?> owner) {
    final var found = new HashMap<String, String>();
    final var seen = new HashSet<String>();
    for (var c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
      for (final Field field : c.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers()) || !seen.add(field.getName())) continue;
        final var named = mappedBy(field.getAnnotation(OneToMany.class));
        if (named != null) found.put(field.getName(), named);
      }
    }
    for (final var name : Beans.propertyNames(owner)) {
      if (found.containsKey(name)) continue;
      final var getter = Beans.propertyGetter(owner, name);
      final var named = getter == null ? null : mappedBy(getter.getAnnotation(OneToMany.class));
      if (named != null) found.put(name, named);
    }
    return Map.copyOf(found);
  }

  private static String mappedBy(final OneToMany annotation) {
    return annotation == null || annotation.mappedBy().isEmpty() ? null : annotation.mappedBy();
  }
}
