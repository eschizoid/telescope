/**
 * Spring Boot 4 autoconfig module for telescope. Provides {@link
 * io.github.eschizoid.telescope.spring.TelescopeAutoConfiguration} and the {@link
 * io.github.eschizoid.telescope.spring.TelescopeMapperRegistry} bean — a typed registry indexing
 * every {@link io.github.eschizoid.telescope.conversion.Mapper} bean in the {@code
 * ApplicationContext} by {@code (sourceClass, targetClass)} pair.
 */
module io.github.eschizoid.telescope.spring {
  requires transitive io.github.eschizoid.telescope;
  requires transitive spring.boot;
  requires transitive spring.boot.autoconfigure;
  requires spring.context;
  requires spring.beans;
  requires spring.core;
  requires static spring.web;
  requires static spring.webmvc;
  requires static jakarta.servlet;
  requires static spring.data.commons;
  requires static org.springdoc.openapi.common;
  requires static io.swagger.v3.core;
  requires static io.swagger.v3.oas.models;

  exports io.github.eschizoid.telescope.spring;
  exports io.github.eschizoid.telescope.spring.web;
}
