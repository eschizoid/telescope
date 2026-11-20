package io.github.eschizoid.telescope.quarkus.rest.runtime;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeRequest;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Function;

/**
 * One mapped body, fixed at startup: the shape the element mapping is applied through, the type the
 * values are checked against, the type the body has once mapped, and for a request the type it is
 * read as.
 *
 * <p>The element mapping is erased because it is chosen per endpoint at startup from beans of any
 * pair; this is the one erased step, at the Quarkus REST boundary.
 *
 * @param shape where the values sit in the body
 * @param source the type the element mapping reads
 * @param element the element mapping
 * @param mappedType the response body's type once mapped; for a request, the parameter's type
 * @param wireClass the raw class a request body is read as, or {@code null} for a response
 * @param wireType the generic type a request body is read as, or {@code null} for a response
 * @param holder whether the parameter is a {@link TelescopeRequest}
 * @param required whether a request body is required
 * @param validation the validation of a request body as read, or {@code null} when none
 * @param validationPath the property path a violation of the body as read starts with
 */
record ResolvedBody(
  ValueShape shape,
  Class<?> source,
  Function<Object, Object> element,
  Type mappedType,
  Class<?> wireClass,
  Type wireType,
  boolean holder,
  boolean required,
  BodyValidation validation,
  String validationPath
) {
  /** Whether a non-null response body is of the shape this endpoint maps. */
  boolean accepts(final Object value) {
    return shape.accepts(value, source);
  }

  /** The response body mapped, or the request body mapped into the parameter's value. */
  Object apply(final Object value) {
    return shape.map(value, element);
  }

  /** The argument for a request body read as {@code original}; {@code null} stays {@code null}. */
  Object argument(final Object original) {
    if (original == null) return null;
    final var mapped = apply(original);
    return holder ? new TelescopeRequest<>(original, mapped) : mapped;
  }

  /**
   * Validates a request body as read. Each value is named by the property path Quarkus gives a
   * resource method's parameter, with a list element's index and a set element's empty brackets.
   */
  void validate(final Object original) {
    final var values = new ArrayList<BodyValidation.Named>();
    if (shape == ValueShape.VALUE) values.add(new BodyValidation.Named(validationPath, original));
    else {
      var index = 0;
      for (final var each : (Collection<?>) original)
        values.add(
          new BodyValidation.Named(validationPath + (shape == ValueShape.SET ? "[]" : "[" + index++ + "]"), each)
        );
    }
    validation.check(values);
  }
}
