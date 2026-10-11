package io.github.eschizoid.telescope.spring.web;

import io.github.eschizoid.telescope.inject.TelescopeProjection;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps a handler method's response body from the type the method returns to {@link #to()}. Spring
 * MVC writes the mapped value; a Java caller of the method still receives what the method returned.
 *
 * <p>Only an annotation can carry this one, so it always sits next to a request mapping: use {@link
 * TelescopeGetMapping} and its siblings. The mapper for the pair is resolved once, at startup: a
 * {@link TelescopeProjection} bean for the pair, then a {@code Mapper} bean, then a generated
 * {@code @Bridge} or the same-name forward mapping. Two candidates fail startup unless {@link
 * #using()} picks one.
 */
@Documented
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TelescopeResponse {
  /**
   * The response type. {@code Void.class} means unset, in which case {@link #using()} supplies it.
   *
   * @return the type the response body is mapped to
   */
  Class<?> to() default Void.class;

  /**
   * The projection to map through, chosen over any other mapper for the pair. {@code
   * TelescopeProjection.class} means unset. The bound is raw so the interface itself can serve as
   * that sentinel.
   *
   * @return the projection interface to map through
   */
  @SuppressWarnings("rawtypes")
  Class<? extends TelescopeProjection> using() default TelescopeProjection.class;
}
