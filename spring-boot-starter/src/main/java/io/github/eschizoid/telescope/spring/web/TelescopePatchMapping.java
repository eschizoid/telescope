package io.github.eschizoid.telescope.spring.web;

import io.github.eschizoid.telescope.inject.TelescopeProjection;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * {@link PatchMapping} whose response body is mapped to {@link #to()}; see {@link
 * TelescopeResponse}.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@RequestMapping(method = RequestMethod.PATCH)
@TelescopeResponse
public @interface TelescopePatchMapping {
  /**
   * See {@link PatchMapping#name()}.
   *
   * @return the PatchMapping name
   */
  @AliasFor(annotation = RequestMapping.class)
  String name() default "";

  /**
   * See {@link PatchMapping#value()}.
   *
   * @return the PatchMapping value
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] value() default {};

  /**
   * See {@link PatchMapping#path()}.
   *
   * @return the PatchMapping path
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] path() default {};

  /**
   * See {@link PatchMapping#params()}.
   *
   * @return the PatchMapping params
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] params() default {};

  /**
   * See {@link PatchMapping#headers()}.
   *
   * @return the PatchMapping headers
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] headers() default {};

  /**
   * See {@link PatchMapping#consumes()}.
   *
   * @return the PatchMapping consumes
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] consumes() default {};

  /**
   * See {@link PatchMapping#produces()}.
   *
   * @return the PatchMapping produces
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] produces() default {};

  /**
   * See {@link PatchMapping#version()}.
   *
   * @return the PatchMapping version
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
