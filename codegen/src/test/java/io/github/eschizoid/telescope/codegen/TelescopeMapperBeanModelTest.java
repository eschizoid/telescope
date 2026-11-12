package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * This module's test classpath has neither Spring nor CDI, which is the situation an application is
 * in when it uses the core annotations without a starter. A container a test needs is declared as a
 * source of the compilation, which the processor finds the way it finds a class on the classpath.
 */
class TelescopeMapperBeanModelTest {

  private static final JavaFileObject PROJECTION = source(
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
  );

  private static final JavaFileObject TRANSFORMER = source(
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
  );

  /** The CDI scope annotation on its own, with none of Quarkus ArC beside it. */
  private static final JavaFileObject CDI_SCOPE = source(
    "jakarta.enterprise.context.ApplicationScoped",
    "package jakarta.enterprise.context; public @interface ApplicationScoped {}"
  );

  private static final JavaFileObject SPRING_COMPONENT = source(
    "org.springframework.stereotype.Component",
    "package org.springframework.stereotype; public @interface Component { String value() default \"\"; }"
  );

  private static final JavaFileObject POST_CONSTRUCT = source(
    "jakarta.annotation.PostConstruct",
    "package jakarta.annotation; public @interface PostConstruct {}"
  );

  /** The class the processor reads as telescope-spring-boot-starter being on the classpath. */
  private static final JavaFileObject SPRING_STARTER = source(
    "io.github.eschizoid.telescope.spring.TelescopeAutoConfiguration",
    "package io.github.eschizoid.telescope.spring; public class TelescopeAutoConfiguration {}"
  );

  /** The class the processor reads as telescope-quarkus being on the classpath. */
  private static final JavaFileObject QUARKUS_STARTER = source(
    "io.github.eschizoid.telescope.quarkus.TelescopeProducer",
    "package io.github.eschizoid.telescope.quarkus; public class TelescopeProducer {}"
  );

  @Test
  @DisplayName("Spring and CDI annotations with no telescope starter are refused until telescope.beans chooses")
  void bothAnnotationsWithoutAStarterAreRefused() {
    final var result = ProcessorHarness.compile(
      List.of(new TelescopeMapperProcessor()),
      SPRING_COMPONENT,
      CDI_SCOPE,
      TRANSFORMER
    );
    assertFalse(result.success());
    assertTrue(
      result.hasError(
        "@TelescopeTransformer finds both Spring and CDI on the classpath of the module that declares this interface," +
          " and neither telescope starter; pass -Atelescope.beans=spring or -Atelescope.beans=cdi"
      ),
      result.errorMessages()
    );
    assertFalse(result.generated().containsKey("demo.NameTransformerImpl"));
  }

  @Test
  @DisplayName("both telescope starters on the classpath are refused until telescope.beans chooses")
  void bothStartersAreRefused() {
    final var result = ProcessorHarness.compile(
      List.of(new TelescopeMapperProcessor()),
      SPRING_STARTER,
      QUARKUS_STARTER,
      SPRING_COMPONENT,
      CDI_SCOPE,
      TRANSFORMER
    );
    assertFalse(result.success());
    assertTrue(
      result.hasError(
        "@TelescopeTransformer finds both telescope-spring-boot-starter and telescope-quarkus on the classpath of the" +
          " module that declares this interface; pass -Atelescope.beans=spring or -Atelescope.beans=cdi"
      ),
      result.errorMessages()
    );
    assertFalse(result.generated().containsKey("demo.NameTransformerImpl"));
  }

  @Test
  @DisplayName("telescope.beans chooses the bean model over both telescope starters")
  void beanModelOptionOverridesBothStarters() {
    final var result = ProcessorHarness.compile(
      List.of(new TelescopeMapperProcessor()),
      List.of("-Atelescope.beans=cdi"),
      SPRING_STARTER,
      QUARKUS_STARTER,
      SPRING_COMPONENT,
      CDI_SCOPE,
      POST_CONSTRUCT,
      TRANSFORMER
    );
    assertTrue(result.success(), result.errorMessages());
    assertTrue(result.generated().get("demo.NameTransformerImpl").contains("@ApplicationScoped"));
  }

  @Test
  @DisplayName("a mapper with neither Spring nor CDI on the classpath is refused on the interface")
  void mapperWithoutAContainerIsRefusedOnTheInterface() {
    final var result = ProcessorHarness.compileFully(List.of(new TelescopeMapperProcessor()), List.of(), PROJECTION);
    assertFalse(result.success());
    assertTrue(
      result.hasError(
        "@TelescopeMapper generates a Spring component or a CDI bean; add telescope-spring-boot-starter or" +
          " telescope-quarkus"
      ),
      result.errorMessages()
    );
    assertFalse(result.generated().containsKey("demo.UserProjectionImpl"));
  }

  @Test
  @DisplayName("a transformer with neither Spring nor CDI on the classpath is refused on the interface")
  void transformerWithoutAContainerIsRefusedOnTheInterface() {
    final var result = ProcessorHarness.compileFully(List.of(new TelescopeMapperProcessor()), List.of(), TRANSFORMER);
    assertFalse(result.success());
    assertTrue(
      result.hasError(
        "@TelescopeTransformer generates a Spring component or a CDI bean; add telescope-spring-boot-starter or" +
          " telescope-quarkus"
      ),
      result.errorMessages()
    );
    assertFalse(result.generated().containsKey("demo.NameTransformerImpl"));
  }

  @Test
  @DisplayName("telescope.beans naming a bean model whose annotations are absent is refused by name")
  void beanModelOptionNamingAnAbsentContainerIsRefused() {
    final var cdi = ProcessorHarness.compile(
      List.of(new TelescopeMapperProcessor()),
      List.of("-Atelescope.beans=cdi"),
      TRANSFORMER
    );
    assertFalse(cdi.success());
    assertTrue(
      cdi.hasError(
        "-Atelescope.beans=cdi asks @TelescopeTransformer for a CDI bean, but" +
          " jakarta.enterprise.context.ApplicationScoped is not on the classpath"
      ),
      cdi.errorMessages()
    );
    final var spring = ProcessorHarness.compile(
      List.of(new TelescopeMapperProcessor()),
      List.of("-Atelescope.beans=spring"),
      CDI_SCOPE,
      TRANSFORMER
    );
    assertFalse(spring.success());
    assertTrue(
      spring.hasError(
        "-Atelescope.beans=spring asks @TelescopeTransformer for a Spring component, but" +
          " org.springframework.stereotype.Component is not on the classpath"
      ),
      spring.errorMessages()
    );
    assertFalse(spring.generated().containsKey("demo.NameTransformerImpl"));
  }

  @Test
  @DisplayName("telescope.beans with a value other than spring or cdi is refused by name")
  void unknownBeanModelIsRefused() {
    final var result = ProcessorHarness.compile(
      List.of(new TelescopeMapperProcessor()),
      List.of("-Atelescope.beans=guice"),
      CDI_SCOPE,
      TRANSFORMER
    );
    assertFalse(result.success());
    assertTrue(
      result.hasError("-Atelescope.beans=guice names no bean model @TelescopeTransformer generates; use spring or cdi"),
      result.errorMessages()
    );
    assertFalse(result.generated().containsKey("demo.NameTransformerImpl"));
  }

  @Test
  @DisplayName("a CDI projection without Quarkus ArC is refused, since it collects customizers through @All")
  void cdiProjectionWithoutArcIsRefused() {
    final var result = ProcessorHarness.compile(List.of(new TelescopeMapperProcessor()), CDI_SCOPE, PROJECTION);
    assertFalse(result.success());
    assertTrue(
      result.hasError(
        "@TelescopeMapper on a TelescopeProjection generates a CDI bean that collects its TelescopeCustomizer beans" +
          " through Quarkus ArC's @All; add telescope-quarkus"
      ),
      result.errorMessages()
    );
    assertFalse(result.generated().containsKey("demo.UserProjectionImpl"));
  }

  @Test
  @DisplayName("a CDI transformer needs no ArC: CDI alone is enough to generate it")
  void cdiTransformerNeedsOnlyTheCdiScope() {
    final var result = ProcessorHarness.compile(
      List.of(new TelescopeMapperProcessor()),
      CDI_SCOPE,
      POST_CONSTRUCT,
      TRANSFORMER
    );
    assertTrue(result.success(), result.errorMessages());
    assertTrue(result.generated().get("demo.NameTransformerImpl").contains("@ApplicationScoped"));
  }
}
