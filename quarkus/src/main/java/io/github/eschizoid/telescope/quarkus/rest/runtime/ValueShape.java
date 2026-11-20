package io.github.eschizoid.telescope.quarkus.rest.runtime;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Where the mapped values sit in a body once Quarkus REST has unwrapped any {@code RestResponse},
 * {@code Uni} or {@code CompletionStage} around it, and how to rebuild the body around mapped
 * values. Every shape passes {@code null} through without calling the element mapping, at the top
 * level and per element.
 */
public enum ValueShape {
  /** One value. */
  VALUE {
    @Override
    boolean accepts(final Object value, final Class<?> source) {
      return source.isInstance(value);
    }

    @Override
    Object map(final Object value, final Function<Object, Object> element) {
      return element.apply(value);
    }

    @Override
    Type type(final Class<?> element) {
      return element;
    }
  },
  /** A {@code List} or {@code Collection}, rebuilt as a list. */
  LIST {
    @Override
    boolean accepts(final Object value, final Class<?> source) {
      return value instanceof Collection<?>;
    }

    @Override
    Object map(final Object value, final Function<Object, Object> element) {
      final var values = (Collection<?>) value;
      final var mapped = new ArrayList<>(values.size());
      for (final var each : values) mapped.add(each == null ? null : element.apply(each));
      return mapped;
    }

    @Override
    Type type(final Class<?> element) {
      return new Generic(List.class, element);
    }
  },
  /** A {@code Set}, rebuilt in its iteration order. */
  SET {
    @Override
    boolean accepts(final Object value, final Class<?> source) {
      return value instanceof Collection<?>;
    }

    @Override
    Object map(final Object value, final Function<Object, Object> element) {
      final var values = (Collection<?>) value;
      final var mapped = LinkedHashSet.newLinkedHashSet(values.size());
      for (final var each : values) mapped.add(each == null ? null : element.apply(each));
      return mapped;
    }

    @Override
    Type type(final Class<?> element) {
      return new Generic(Set.class, element);
    }
  },
  /** An {@code Optional}. */
  OPTIONAL {
    @Override
    boolean accepts(final Object value, final Class<?> source) {
      return value instanceof Optional<?> optional && optional.map(source::isInstance).orElse(true);
    }

    @Override
    Object map(final Object value, final Function<Object, Object> element) {
      return ((Optional<?>) value).map(element::apply);
    }

    @Override
    Type type(final Class<?> element) {
      return new Generic(Optional.class, element);
    }
  };

  /**
   * Whether a non-null {@code value} has this shape and holds {@code source} values. A body of
   * another type, such as one an exception mapper wrote, does not.
   */
  abstract boolean accepts(final Object value, final Class<?> source);

  /** Rebuilds a non-null {@code value} with {@code element} applied to each value it holds. */
  abstract Object map(final Object value, final Function<Object, Object> element);

  /** The body's generic type when it holds {@code element} values. */
  abstract Type type(final Class<?> element);

  /** {@code raw<argument>}, built without reflection. */
  record Generic(Class<?> raw, Type argument) implements ParameterizedType {
    @Override
    public Type[] getActualTypeArguments() {
      return new Type[] { argument };
    }

    @Override
    public Type getRawType() {
      return raw;
    }

    @Override
    public Type getOwnerType() {
      return null;
    }

    @Override
    public String getTypeName() {
      return raw.getName() + "<" + argument.getTypeName() + ">";
    }

    @Override
    public String toString() {
      return getTypeName();
    }
  }
}
