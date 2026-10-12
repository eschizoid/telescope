package io.github.eschizoid.telescope.spring.web;

import io.github.eschizoid.telescope.inject.TelescopeProjection;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Reads a handler parameter's request body as {@link #from()} and maps it to the parameter's type
 * before the handler runs. It replaces {@code @RequestBody}; {@code @Valid} on the parameter
 * validates the body as read, before it is mapped.
 *
 * <pre>{@code
 * @PostMapping("/api/v1/users")
 * UserDto create(@TelescopeRequestBody(from = CreateUserRequest.class) @Valid UserDto user) { ... }
 * }</pre>
 *
 * <p>The parameter may be {@code T}, {@code List<T>}, {@code Collection<T>} or {@code Set<T>}. A
 * {@link TelescopeRequest} parameter carries the body as read and as mapped, takes both types from
 * its type arguments, and needs this annotation only for {@link #using()} or {@link #required()}.
 * The mapper is resolved once, at startup, in the order {@link TelescopeResponse} describes.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface TelescopeRequestBody {
  /**
   * The type the body is read as. {@code Void.class} means unset, in which case {@link #using()}
   * supplies it. Refused on a {@link TelescopeRequest} parameter, whose first type argument is it.
   *
   * @return the wire type
   */
  Class<?> from() default Void.class;

  /**
   * The projection to map through, chosen over any other mapper for the pair. {@code
   * TelescopeProjection.class} means unset.
   *
   * @return the projection interface to map through
   */
  @SuppressWarnings("rawtypes")
  Class<? extends TelescopeProjection> using() default TelescopeProjection.class;

  /**
   * Whether a body is required. A missing required body is a {@code 400}; a missing optional one
   * reaches the handler as {@code null}.
   *
   * @return whether the body is required
   */
  boolean required() default true;
}
