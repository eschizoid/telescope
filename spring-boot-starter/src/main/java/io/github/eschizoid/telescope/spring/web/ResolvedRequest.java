package io.github.eschizoid.telescope.spring.web;

import java.lang.reflect.Type;
import java.util.function.Function;

/**
 * One handler parameter's request-body mapping, fixed at startup: the type the body is read as and
 * its raw class, the shape the element mapping is applied through, whether the parameter is a
 * {@link TelescopeRequest} holder, and whether a body is required.
 */
record ResolvedRequest(
  ResponseShape shape,
  Function<Object, Object> element,
  Type wireType,
  Class<?> wireClass,
  boolean holder,
  boolean required
) {
  /** The handler argument for a body read as {@code original}; {@code null} stays {@code null}. */
  Object argument(final Object original) {
    if (original == null) return null;
    final var mapped = shape.map(original, element);
    return holder ? new TelescopeRequest<>(original, mapped) : mapped;
  }
}
