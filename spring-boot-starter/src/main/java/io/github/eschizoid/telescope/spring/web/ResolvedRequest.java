package io.github.eschizoid.telescope.spring.web;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * One handler parameter's request-body mapping, fixed at startup: the type the body is read as and
 * the parameter view Spring's body reader is given for it, the shape the element mapping is applied
 * through, whether the parameter is a {@link TelescopeRequest} holder, whether a body is required,
 * and the validation hints of the parameter's {@code @Valid} or {@code @Validated}, or {@code null}
 * when it carries neither.
 */
record ResolvedRequest(
  ResponseShape shape,
  Function<Object, Object> element,
  Type wireType,
  WireParameter wire,
  boolean holder,
  boolean required,
  Object[] validationHints
) {
  /** The handler argument for a body read as {@code original}; {@code null} stays {@code null}. */
  Object argument(final Object original) {
    if (original == null) return null;
    final var mapped = shape.map(original, element);
    return holder ? new TelescopeRequest<>(original, mapped) : mapped;
  }

  /** The values the body holds: the body itself, or each element of a list or set body. */
  Iterable<?> values(final Object original) {
    return shape instanceof ResponseShape.Single ? List.of(original) : (Collection<?>) original;
  }
}
