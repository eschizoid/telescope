package io.github.eschizoid.telescope.spring.web;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.core.ResolvableType;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Where the source values sit in a handler's return type, and how to rebuild that type around
 * mapped values. Every shape passes {@code null} through without calling the element mapping, at
 * the top level and per element.
 *
 * <p>{@code Page} is matched by name, so this type loads without Spring Data; a method whose return
 * type names {@code Page} has Spring Data on its classpath by construction.
 */
sealed interface ResponseShape {
  String PAGE = "org.springframework.data.domain.Page";

  // Non-generic types that are never a domain value to map. A generic type (Map, Stream, Future,
  // DeferredResult, Callable, HttpEntity, ...) is refused earlier, by its type parameters.
  List<Class<?>> NOT_SOURCES = List.of(
    CharSequence.class,
    Number.class,
    Boolean.class,
    Character.class,
    ResponseBodyEmitter.class,
    StreamingResponseBody.class
  );

  /** The type the mapping reads. */
  Class<?> source();

  /**
   * Whether {@code value} has this shape: {@code null}, or the container this shape rebuilds
   * holding source values. A {@code ResponseEntity<S>} can carry a body of another type, such as
   * the {@code ProblemDetail} that {@code ResponseEntity.of(problem).build()} produces for any
   * {@code T}; such a value is written as it is.
   */
  boolean accepts(Object value);

  /** Rebuilds {@code value} with {@code element} applied to each source value it holds. */
  Object map(Object value, Function<Object, Object> element);

  /** The return type Spring MVC sees once the value is mapped to {@code target}. */
  Type mappedType(Class<?> target);

  /**
   * The response body's type: {@link #mappedType} without an enclosing {@code ResponseEntity} or
   * {@code Optional}.
   */
  default Type bodyType(final Class<?> target) {
    return mappedType(target);
  }

  /**
   * Whether the method returns a {@code ResponseEntity}, which writes a body even outside a
   * {@code @ResponseBody}.
   */
  default boolean isEntity() {
    return false;
  }

  static ResponseShape of(final Type returnType) {
    if (returnType instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
      final var argument = parameterized.getActualTypeArguments()[0];
      if (raw == ResponseEntity.class) {
        final var inner = of(argument);
        if (inner.isEntity()) throw unsupported(returnType);
        return new Entity(inner);
      }
      if (raw == List.class || raw == Collection.class) return new ListOf(source(argument, returnType));
      if (raw == Set.class) return new SetOf(source(argument, returnType));
      if (raw == Optional.class) return new OptionalOf(source(argument, returnType));
      if (raw.getName().equals(PAGE)) return new PageOf(source(argument, returnType));
      throw unsupported(returnType);
    }
    return new Single(source(returnType, returnType));
  }

  private static Class<?> source(final Type type, final Type returnType) {
    if (
      !(type instanceof Class<?> candidate) ||
      candidate.isPrimitive() ||
      candidate.isArray() ||
      candidate == Object.class ||
      candidate == Void.class ||
      candidate.getTypeParameters().length > 0 ||
      NOT_SOURCES.stream().anyMatch(excluded -> excluded.isAssignableFrom(candidate))
    ) throw unsupported(returnType);
    return candidate;
  }

  private static IllegalArgumentException unsupported(final Type returnType) {
    return new IllegalArgumentException(
      "unsupported return type " +
        returnType.getTypeName() +
        ". A mapped response returns S, List<S>, Collection<S>, Set<S>, Optional<S>, Page<S>, or a ResponseEntity of" +
        " one of those, where S is the type the mapper reads."
    );
  }

  private static Type generic(final Class<?> raw, final Type argument) {
    return ResolvableType.forClassWithGenerics(raw, ResolvableType.forType(argument)).getType();
  }

  record Single(Class<?> source) implements ResponseShape {
    @Override
    public boolean accepts(final Object value) {
      return value == null || source.isInstance(value);
    }

    @Override
    public Object map(final Object value, final Function<Object, Object> element) {
      return value == null ? null : element.apply(value);
    }

    @Override
    public Type mappedType(final Class<?> target) {
      return target;
    }
  }

  record ListOf(Class<?> source) implements ResponseShape {
    @Override
    public boolean accepts(final Object value) {
      return value == null || value instanceof Collection<?>;
    }

    @Override
    public Object map(final Object value, final Function<Object, Object> element) {
      if (value == null) return null;
      final var values = (Collection<?>) value;
      final var mapped = new ArrayList<>(values.size());
      for (final var each : values) mapped.add(each == null ? null : element.apply(each));
      return mapped;
    }

    @Override
    public Type mappedType(final Class<?> target) {
      return generic(List.class, target);
    }
  }

  record SetOf(Class<?> source) implements ResponseShape {
    @Override
    public boolean accepts(final Object value) {
      return value == null || value instanceof Collection<?>;
    }

    @Override
    public Object map(final Object value, final Function<Object, Object> element) {
      if (value == null) return null;
      final var values = (Collection<?>) value;
      final var mapped = LinkedHashSet.newLinkedHashSet(values.size());
      for (final var each : values) mapped.add(each == null ? null : element.apply(each));
      return mapped;
    }

    @Override
    public Type mappedType(final Class<?> target) {
      return generic(Set.class, target);
    }
  }

  record OptionalOf(Class<?> source) implements ResponseShape {
    @Override
    public boolean accepts(final Object value) {
      return value == null || (value instanceof Optional<?> optional && optional.map(source::isInstance).orElse(true));
    }

    @Override
    public Object map(final Object value, final Function<Object, Object> element) {
      return value == null ? null : ((Optional<?>) value).map(element::apply);
    }

    @Override
    public Type mappedType(final Class<?> target) {
      return generic(Optional.class, target);
    }

    @Override
    public Type bodyType(final Class<?> target) {
      return target;
    }
  }

  record PageOf(Class<?> source) implements ResponseShape {
    @Override
    public boolean accepts(final Object value) {
      return value == null || value instanceof Page<?>;
    }

    @Override
    public Object map(final Object value, final Function<Object, Object> element) {
      return value == null ? null : ((Page<?>) value).map(element::apply);
    }

    @Override
    public Type mappedType(final Class<?> target) {
      return generic(Page.class, target);
    }
  }

  record Entity(ResponseShape body) implements ResponseShape {
    @Override
    public boolean accepts(final Object value) {
      return value == null || (value instanceof ResponseEntity<?> entity && body.accepts(entity.getBody()));
    }

    @Override
    public Class<?> source() {
      return body.source();
    }

    @Override
    public Object map(final Object value, final Function<Object, Object> element) {
      if (value == null) return null;
      final var entity = (ResponseEntity<?>) value;
      return ResponseEntity.status(entity.getStatusCode())
        .headers(entity.getHeaders())
        .body(body.map(entity.getBody(), element));
    }

    @Override
    public Type mappedType(final Class<?> target) {
      return generic(ResponseEntity.class, body.mappedType(target));
    }

    @Override
    public Type bodyType(final Class<?> target) {
      return body.bodyType(target);
    }

    @Override
    public boolean isEntity() {
      return true;
    }
  }
}
