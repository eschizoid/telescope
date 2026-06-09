package io.github.eschizoid.telescope.demo.invoicing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the invoicing demo.
 *
 * <p>The story this submodule tells, in one line: {@code @Bridge}-annotated record↔bean pairs get
 * conversion classes emitted at compile time, with deep recursion into other user-declared bridges.
 *
 * <p>No JPA layer, no autoconfig magic — just records and beans annotated with {@code @Focus},
 * {@code @BeanFocus}, and {@code @Bridge}, plus a thin controller that exposes the generated
 * forward / backward calls over HTTP.
 */
@SpringBootApplication
public class DemoApplication {

  static void main(final String[] args) {
    SpringApplication.run(DemoApplication.class, args);
  }
}
