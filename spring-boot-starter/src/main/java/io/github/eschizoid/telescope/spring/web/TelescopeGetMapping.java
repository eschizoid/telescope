package io.github.eschizoid.telescope.spring.web;

import io.github.eschizoid.telescope.inject.TelescopeProjection;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * {@link GetMapping} whose response body is mapped to {@link #to()}; see {@link TelescopeResponse}.
 *
 * <pre>{@code
 * @TelescopeGetMapping(value = "/users/{id}", to = UserDto.class)
 * User user(@PathVariable String id) { return service.findUser(id); }
 * }</pre>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@RequestMapping(method = RequestMethod.GET)
@TelescopeResponse
public @interface TelescopeGetMapping {
  /**
   * See {@link GetMapping#name()}.
   *
   * @return the GetMapping name
   */
  @AliasFor(annotation = RequestMapping.class)
  String name() default "";

  /**
   * See {@link GetMapping#value()}.
   *
   * @return the GetMapping value
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] value() default {};

  /**
   * See {@link GetMapping#path()}.
   *
   * @return the GetMapping path
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] path() default {};

  /**
   * See {@link GetMapping#params()}.
   *
   * @return the GetMapping params
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] params() default {};

  /**
   * See {@link GetMapping#headers()}.
   *
   * @return the GetMapping headers
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] headers() default {};

  /**
   * See {@link GetMapping#consumes()}.
   *
   * @return the GetMapping consumes
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] consumes() default {};

  /**
   * See {@link GetMapping#produces()}.
   *
   * @return the GetMapping produces
   */
  @AliasFor(annotation = RequestMapping.class)
  String[] produces() default {};

  /**
   * See {@link GetMapping#version()}.
   *
   * @return the GetMapping version
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
