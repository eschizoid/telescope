package io.github.eschizoid.telescope.demo.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 4 entry point. Hands the JVM over to Spring's auto-configuration, which:
 *
 * <ol>
 *   <li>Boots an embedded Tomcat (or Jetty/Undertow if swapped) on port 8080.
 *   <li>Wires Spring Data JPA + Hibernate 7 against the in-memory H2 database declared in {@code
 *       application.yml}.
 *   <li>Registers the two controllers ({@code RuntimeOrderController} / {@code
 *       CodegenOrderController}) and the mapper {@code @Bean}s / {@code @Component}s they depend
 *       on.
 *   <li>Hands Jackson the record types — Spring's auto-configured {@code ObjectMapper} picks up
 *       {@code jackson-module-parameter-names} so canonical-constructor binding works without extra
 *       annotations.
 * </ol>
 *
 * <p>Smoke-test by curl: see {@code README.md}.
 */
@SpringBootApplication
public class DemoApplication {

  static void main(final String[] args) {
    SpringApplication.run(DemoApplication.class, args);
  }
}
