/**
 * An application module that uses the starter's mapped responses from the module path, the way a
 * modular Spring application does. Its package is opened unqualified: Spring invokes the handler,
 * Jackson writes the response, and telescope reads the source, each from its own module.
 */
module telescope.spring.smoke {
  requires io.github.eschizoid.telescope.spring;
  requires jakarta.servlet;
  requires spring.test;
  requires spring.web;
  requires spring.webmvc;
  requires static spring.data.commons;
  requires static org.springdoc.openapi.common;
  requires static io.swagger.v3.oas.models;

  opens io.github.eschizoid.telescope.spring.smoke;
}
