package io.github.eschizoid.telescope.spring.web;

import io.github.eschizoid.telescope.inject.TelescopeProjection;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * {@link PutMapping} whose response body is mapped to {@link #to()}; see {@link TelescopeResponse}.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@RequestMapping(method = RequestMethod.PUT)
@TelescopeResponse
public @interface TelescopePutMapping {
  /**
   * See {@link PutMapping#name()}.
   *
   * @return the PutMapping name
   */
  @AliasFor(annotation = RequestMapping.class)
  String name() default "";

  /**
   * See {@link PutMapping#value()}.
   *
   * @return the PutMapping value
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] value() default {};

  /**
   * See {@link PutMapping#path()}.
   *
   * @return the PutMapping path
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] path() default {};

  /**
   * See {@link PutMapping#params()}.
   *
   * @return the PutMapping params
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] params() default {};

  /**
   * See {@link PutMapping#headers()}.
   *
   * @return the PutMapping headers
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] headers() default {};

  /**
   * See {@link PutMapping#consumes()}.
   *
   * @return the PutMapping consumes
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] consumes() default {};

  /**
   * See {@link PutMapping#produces()}.
   *
   * @return the PutMapping produces
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] produces() default {};

  /**
   * See {@link PutMapping#version()}.
   *
   * @return the PutMapping version
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
