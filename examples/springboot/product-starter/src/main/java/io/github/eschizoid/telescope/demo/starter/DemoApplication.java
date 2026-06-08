package io.github.eschizoid.telescope.demo.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boots the product-starter demo. {@code @SpringBootApplication} alone is enough — the {@code
 * telescope-spring-boot-starter} dependency contributes {@code TelescopeAutoConfiguration} via
 * Spring Boot 4's autoconfig SPI ({@code META-INF/spring/...AutoConfiguration.imports}), so the
 * {@link io.github.eschizoid.telescope.spring.TelescopeMapperRegistry} bean is wired automatically.
 */
@SpringBootApplication
public class DemoApplication {

  public static void main(final String[] args) {
    SpringApplication.run(DemoApplication.class, args);
  }
}
