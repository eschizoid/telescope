package io.github.eschizoid.telescope.spring.web;

import io.github.eschizoid.telescope.inject.TelescopeProjection;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * {@link DeleteMapping} whose response body is mapped to {@link #to()}; see {@link
 * TelescopeResponse}.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@RequestMapping(method = RequestMethod.DELETE)
@TelescopeResponse
public @interface TelescopeDeleteMapping {
  /**
   * See {@link DeleteMapping#name()}.
   *
   * @return the DeleteMapping name
   */
  @AliasFor(annotation = RequestMapping.class)
  String name() default "";

  /**
   * See {@link DeleteMapping#value()}.
   *
   * @return the DeleteMapping value
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] value() default {};

  /**
   * See {@link DeleteMapping#path()}.
   *
   * @return the DeleteMapping path
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] path() default {};

  /**
   * See {@link DeleteMapping#params()}.
   *
   * @return the DeleteMapping params
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] params() default {};

  /**
   * See {@link DeleteMapping#headers()}.
   *
   * @return the DeleteMapping headers
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] headers() default {};

  /**
   * See {@link DeleteMapping#consumes()}.
   *
   * @return the DeleteMapping consumes
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] consumes() default {};

  /**
   * See {@link DeleteMapping#produces()}.
   *
   * @return the DeleteMapping produces
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] produces() default {};

  /**
   * See {@link DeleteMapping#version()}.
   *
   * @return the DeleteMapping version
   */
  @AliasFor(annotation = RequestMapping.class)
  String version() default "";

  /**
   * See {@link TelescopeResponse#to()}.
   *
   * @return the type the response body is mapped to
   */
  @AliasFor(annotation = TelescopeResponse.class)
  Class<?> to() default Void.class;

  /**
   * See {@link TelescopeResponse#using()}.
   *
   * @return the projection interface to map through
   */
  @SuppressWarnings("rawtypes")
  @AliasFor(annotation = TelescopeResponse.class)
  Class<? extends TelescopeProjection> using() default TelescopeProjection.class;
}
