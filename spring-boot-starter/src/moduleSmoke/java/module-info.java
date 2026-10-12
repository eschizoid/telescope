/**
 * An application module that uses the starter's mapped responses from the module path, the way a
 * modular Spring application does. Its package is opened, as telescope's JPMS setup asks of an
 * application module.
 */
module telescope.spring.smoke {
  requires io.github.eschizoid.telescope.spring;
  requires jakarta.servlet;
  requires spring.test;
  requires spring.web;
  requires spring.webmvc;

  opens io.github.eschizoid.telescope.spring.smoke;
}
