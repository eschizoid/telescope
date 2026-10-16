package io.github.eschizoid.telescope.spring.web;

import java.lang.reflect.Type;
import java.util.function.Function;

/**
 * One handler method's mapping, fixed at startup: the element mapping, the shape it is applied
 * through, the return type Spring MVC sees afterwards and its raw class, and the body type the API
 * documentation shows.
 *
 * <p>The element mapping is erased because it is chosen per endpoint at run time from beans of any
 * pair; this is the one erased step, at the Spring boundary.
 */
record ResolvedResponse(
  ResponseShape shape,
  Function<Object, Object> element,
  Type mappedType,
  Class<?> mappedClass,
  Type bodyType
) {
  Object apply(final Object value) {
    return shape.map(value, element);
  }
}
