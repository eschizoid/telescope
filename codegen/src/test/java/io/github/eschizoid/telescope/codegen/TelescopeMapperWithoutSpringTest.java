package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * This module's test classpath has no Spring, which is the situation an application is in when it
 * uses the core annotations without the Spring starter.
 */
class TelescopeMapperWithoutSpringTest {

  @Test
  void mapperWithoutSpringIsRefusedOnTheInterface() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.UserProjection",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.TelescopeMapper;
        import io.github.eschizoid.telescope.inject.TelescopeProjection;
        record User(String name) {}
        record UserDto(String name) {}
        @TelescopeMapper
        interface UserProjection extends TelescopeProjection<User, UserDto> {}
        """
      )
    );
    assertFalse(result.success());
    assertTrue(
      result.hasError("@TelescopeMapper generates a Spring component; add telescope-spring-boot-starter"),
      result.errorMessages()
    );
    assertFalse(result.generated().containsKey("demo.UserProjectionImpl"));
  }

  @Test
  void transformerWithoutSpringIsRefusedOnTheInterface() {
    final var result = ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor()),
      List.of(),
      source(
        "demo.NameTransformer",
        """
        package demo;
        import io.github.eschizoid.telescope.Telescope;
        import io.github.eschizoid.telescope.annotations.TelescopeTransformer;
        import io.github.eschizoid.telescope.inject.TelescopeTransformation;
        import io.github.eschizoid.telescope.inject.Transformation;
        record User(String name) {}
        @TelescopeTransformer
        interface NameTransformer extends TelescopeTransformation<User, String> {
          default Telescope<User, String> path() { return Telescope.of(User.class).field(User::name); }
          default Transformation<String> transform() { return new Transformation<>(null, String::strip); }
        }
        """
      )
    );
    assertFalse(result.success());
    assertTrue(
      result.hasError("@TelescopeTransformer generates a Spring component; add telescope-spring-boot-starter"),
      result.errorMessages()
    );
    assertFalse(result.generated().containsKey("demo.NameTransformerImpl"));
  }
}
