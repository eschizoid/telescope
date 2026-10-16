package io.github.eschizoid.telescope.spring.web;

import io.github.eschizoid.telescope.inject.TelescopeProjection;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * {@link PostMapping} whose response body is mapped to {@link #to()}; see {@link
 * TelescopeResponse}.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@RequestMapping(method = RequestMethod.POST)
@TelescopeResponse
public @interface TelescopePostMapping {
  /**
   * See {@link PostMapping#name()}.
   *
   * @return the PostMapping name
   */
  @AliasFor(annotation = RequestMapping.class)
  String name() default "";

  /**
   * See {@link PostMapping#value()}.
   *
   * @return the PostMapping value
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] value() default {};

  /**
   * See {@link PostMapping#path()}.
   *
   * @return the PostMapping path
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] path() default {};

  /**
   * See {@link PostMapping#params()}.
   *
   * @return the PostMapping params
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] params() default {};

  /**
   * See {@link PostMapping#headers()}.
   *
   * @return the PostMapping headers
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] headers() default {};

  /**
   * See {@link PostMapping#consumes()}.
   *
   * @return the PostMapping consumes
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] consumes() default {};

  /**
   * See {@link PostMapping#produces()}.
   *
   * @return the PostMapping produces
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] produces() default {};

  /**
   * See {@link PostMapping#version()}.
   *
   * @return the PostMapping version
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
