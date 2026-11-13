package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The text {@code TelescopeMapperProcessor} writes for every bean shape, as a Spring component and
 * as a CDI bean, from one compilation each. This module's classpath holds neither container, so
 * each container's annotations, and the telescope starter classes, are declared as sources, which
 * the processor finds the way it finds a class on the classpath.
 */
class TelescopeMapperBeanEmissionTest {

  private static final List<JavaFileObject> MODELS = List.of(
    source(
      "demo.User",
      """
      package demo;
      import io.github.eschizoid.telescope.annotations.Bridge;
      @Bridge(UserDto.class)
      public record User(String name, String city) {}
      """
    ),
    source("demo.UserDto", "package demo; public record UserDto(String name, String city) {}"),
    source("demo.Account", "package demo; public record Account(String owner) {}"),
    source("demo.AccountDto", "package demo; public record AccountDto(String owner) {}")
  );

  /**
   * One interface per bean shape: projections with and without a bridge, plain mappers,
   * transformers.
   */
  private static final List<JavaFileObject> BLUEPRINTS = List.of(
    source(
      "demo.BridgedProjection",
      """
      package demo;
      import io.github.eschizoid.telescope.annotations.TelescopeMapper;
      import io.github.eschizoid.telescope.inject.TelescopeProjection;
      @TelescopeMapper(value = "bridged", transformers = CityTransformer.class)
      public interface BridgedProjection extends TelescopeProjection<User, UserDto> {}
      """
    ),
    source(
      "demo.AccountProjection",
      """
      package demo;
      import io.github.eschizoid.telescope.annotations.TelescopeMapper;
      import io.github.eschizoid.telescope.inject.TelescopeProjection;
      @TelescopeMapper
      public interface AccountProjection extends TelescopeProjection<Account, AccountDto> {}
      """
    ),
    source(
      "demo.UserMapper",
      """
      package demo;
      import io.github.eschizoid.telescope.annotations.TelescopeMapper;
      @TelescopeMapper(from = User.class, to = UserDto.class)
      public interface UserMapper { UserDto map(User user); }
      """
    ),
    source(
      "demo.CityUserMapper",
      """
      package demo;
      import io.github.eschizoid.telescope.annotations.TelescopeMapper;
      @TelescopeMapper(from = User.class, to = UserDto.class, transformers = CityTransformer.class)
      public interface CityUserMapper { UserDto map(User user); }
      """
    ),
    source(
      "demo.CityTransformer",
      """
      package demo;
      import io.github.eschizoid.telescope.Telescope;
      import io.github.eschizoid.telescope.annotations.TelescopeTransformer;
      import io.github.eschizoid.telescope.inject.TelescopeTransformation;
      import io.github.eschizoid.telescope.inject.Transformation;
      @TelescopeTransformer("city")
      public interface CityTransformer extends TelescopeTransformation<User, String> {
        @Override default Telescope<User, String> path() { return Telescope.of(User.class).field(User::city); }
        @Override default Transformation<String> transform() { return new Transformation<>(null, String::strip); }
      }
      """
    ),
    source(
      "demo.NameTransformer",
      """
      package demo;
      import io.github.eschizoid.telescope.Telescope;
      import io.github.eschizoid.telescope.annotations.TelescopeTransformer;
      import io.github.eschizoid.telescope.inject.TelescopeTransformation;
      import io.github.eschizoid.telescope.inject.Transformation;
      @TelescopeTransformer
      public interface NameTransformer extends TelescopeTransformation<User, String> {
        @Override default Telescope<User, String> path() { return Telescope.of(User.class).field(User::name); }
        @Override default Transformation<String> transform() { return new Transformation<>(null, String::strip); }
      }
      """
    )
  );

  private static final List<JavaFileObject> SPRING = List.of(
    source(
      "org.springframework.stereotype.Component",
      "package org.springframework.stereotype; public @interface Component { String value() default \"\"; }"
    ),
    source(
      "org.springframework.beans.factory.ObjectProvider",
      """
      package org.springframework.beans.factory;
      import java.util.stream.Stream;
      public interface ObjectProvider<T> { Stream<T> orderedStream(); }
      """
    )
  );

  private static final List<JavaFileObject> CDI = List.of(
    source(
      "jakarta.enterprise.context.ApplicationScoped",
      "package jakarta.enterprise.context; public @interface ApplicationScoped {}"
    ),
    source("jakarta.inject.Inject", "package jakarta.inject; public @interface Inject {}"),
    source("jakarta.inject.Named", "package jakarta.inject; public @interface Named { String value(); }"),
    source("jakarta.annotation.PostConstruct", "package jakarta.annotation; public @interface PostConstruct {}"),
    source("io.quarkus.arc.All", "package io.quarkus.arc; public @interface All {}")
  );

  private static final JavaFileObject SPRING_STARTER = source(
    "io.github.eschizoid.telescope.spring.TelescopeAutoConfiguration",
    "package io.github.eschizoid.telescope.spring; public class TelescopeAutoConfiguration {}"
  );

  private static final JavaFileObject QUARKUS_STARTER = source(
    "io.github.eschizoid.telescope.quarkus.TelescopeProducer",
    "package io.github.eschizoid.telescope.quarkus; public class TelescopeProducer {}"
  );

  @SafeVarargs
  private static Map<String, String> generate(final List<String> options, final List<JavaFileObject>... groups) {
    final var sources = new ArrayList<JavaFileObject>(MODELS);
    sources.addAll(BLUEPRINTS);
    for (final var group : groups) sources.addAll(group);
    final var result = ProcessorHarness.compile(
      List.of(new TelescopeMapperProcessor(), new BridgeProcessor()),
      options,
      sources.toArray(JavaFileObject[]::new)
    );
    assertTrue(result.success(), result.errorMessages());
    return result.generated();
  }

  @Test
  @DisplayName("with CDI alone, every bean shape is an @ApplicationScoped bean the client proxy can subclass")
  void everyShapeAsACdiBean() {
    final var generated = generate(List.of(), CDI);
    final var bridged = generated.get("demo.BridgedProjectionImpl");
    final var account = generated.get("demo.AccountProjectionImpl");
    final var plain = generated.get("demo.UserMapperImpl");
    final var withTransformer = generated.get("demo.CityUserMapperImpl");
    final var city = generated.get("demo.CityTransformerImpl");
    final var name = generated.get("demo.NameTransformerImpl");
    assertAll(
      () ->
        assertTrue(
          bridged.contains("@ApplicationScoped\n@Named(\"bridged\")\npublic class BridgedProjectionImpl"),
          bridged
        ),
      () ->
        assertTrue(
          bridged.contains("  protected BridgedProjectionImpl() {\n    this.transformers = null;\n  }"),
          bridged
        ),
      () ->
        assertTrue(
          bridged.contains(
            "  @Inject\n  @SuppressWarnings(\"this-escape\")\n  public BridgedProjectionImpl(final CityTransformer transformer0," +
              " @All final List<TelescopeCustomizer<BridgedProjection>> customizers) {\n    pending = "
          ),
          bridged
        ),
      () -> assertTrue(bridged.contains("    customizers.forEach(customizer -> customizer.customize(this));"), bridged),
      () -> assertTrue(bridged.contains("return UserBridge.forward(value);"), bridged),
      () -> assertFalse(bridged.contains("mapping"), bridged),
      () -> assertTrue(account.contains("@ApplicationScoped\npublic class AccountProjectionImpl"), account),
      () -> assertTrue(account.contains("  private final Mapper<Account, AccountDto> mapping;\n"), account),
      () ->
        assertTrue(
          account.contains(
            "  protected AccountProjectionImpl() {\n    this.mapping = null;\n    this.transformers = null;\n  }"
          ),
          account
        ),
      () -> assertTrue(account.contains(" customizers) {\n    this.mapping = createMapping();\n"), account),
      () ->
        assertTrue(plain.contains("@ApplicationScoped\npublic class UserMapperImpl implements UserMapper {"), plain),
      () -> assertFalse(plain.contains("UserMapperImpl("), plain),
      () -> assertFalse(plain.contains("@Inject"), plain),
      () ->
        assertTrue(
          withTransformer.contains(
            "  protected CityUserMapperImpl() {\n    this.transformers = null;\n  }\n  @Inject\n" +
              "  public CityUserMapperImpl(final CityTransformer transformer0) {"
          ),
          withTransformer
        ),
      () -> assertFalse(withTransformer.contains("SuppressWarnings"), withTransformer),
      () -> assertTrue(city.contains("@ApplicationScoped\n@Named(\"city\")\npublic class CityTransformerImpl"), city),
      () -> assertTrue(city.contains("  @PostConstruct\n  void $telescopeCachePathAndTransform() {\n"), city),
      () -> assertTrue(city.contains("  private Telescope<User, String> path;\n"), city),
      () -> assertTrue(name.contains("@ApplicationScoped\npublic class NameTransformerImpl"), name),
      () -> assertFalse(name.contains("Named"), name)
    );
  }

  @Test
  @DisplayName("with Spring alone, every bean shape is a final @Component that builds its state in field initializers")
  void everyShapeAsASpringComponent() {
    final var generated = generate(List.of(), SPRING);
    final var bridged = generated.get("demo.BridgedProjectionImpl");
    final var account = generated.get("demo.AccountProjectionImpl");
    final var plain = generated.get("demo.UserMapperImpl");
    final var withTransformer = generated.get("demo.CityUserMapperImpl");
    final var city = generated.get("demo.CityTransformerImpl");
    final var name = generated.get("demo.NameTransformerImpl");
    assertAll(
      () -> assertTrue(bridged.contains("@Component(\"bridged\")\npublic final class BridgedProjectionImpl"), bridged),
      () ->
        assertTrue(
          bridged.contains(
            "  public BridgedProjectionImpl(final CityTransformer transformer0," +
              " final ObjectProvider<TelescopeCustomizer<BridgedProjection>> customizers) {"
          ),
          bridged
        ),
      () ->
        assertTrue(
          bridged.contains("customizers.orderedStream().forEach(customizer -> customizer.customize(this));"),
          bridged
        ),
      () -> assertFalse(bridged.contains("protected"), bridged),
      () -> assertFalse(bridged.contains("@Inject"), bridged),
      () -> assertTrue(account.contains("@Component(\"\")\npublic final class AccountProjectionImpl"), account),
      () ->
        assertTrue(
          account.contains("  private final Mapper<Account, AccountDto> mapping = createMapping();\n"),
          account
        ),
      () -> assertFalse(account.contains("this.mapping"), account),
      () -> assertTrue(plain.contains("@Component(\"\")\npublic final class UserMapperImpl"), plain),
      () ->
        assertTrue(
          withTransformer.contains("  public CityUserMapperImpl(final CityTransformer transformer0) {"),
          withTransformer
        ),
      () -> assertFalse(withTransformer.contains("protected"), withTransformer),
      () -> assertTrue(city.contains("@Component(\"city\")\npublic final class CityTransformerImpl"), city),
      () -> assertTrue(city.contains("  private final Telescope<User, String> path =\n"), city),
      () -> assertFalse(city.contains("PostConstruct"), city),
      () -> assertTrue(name.contains("@Component(\"\")\npublic final class NameTransformerImpl"), name)
    );
  }

  @Test
  @DisplayName("a telescope starter alone picks its own container, whatever annotations of the other sit beside it")
  void eachStarterPicksItsOwnContainer() {
    final var spring = generate(List.of(), SPRING, CDI, List.of(SPRING_STARTER));
    assertTrue(
      spring.get("demo.NameTransformerImpl").contains("@Component(\"\")"),
      spring.get("demo.NameTransformerImpl")
    );
    final var cdi = generate(List.of(), SPRING, CDI, List.of(QUARKUS_STARTER));
    assertTrue(cdi.get("demo.NameTransformerImpl").contains("@ApplicationScoped"), cdi.get("demo.NameTransformerImpl"));
  }

  @Test
  @DisplayName("telescope.beans=spring overrides both starters and the CDI annotations")
  void springOptionOverridesBothStarters() {
    final var generated = generate(
      List.of("-Atelescope.beans=spring"),
      SPRING,
      CDI,
      List.of(SPRING_STARTER, QUARKUS_STARTER)
    );
    final var name = generated.get("demo.NameTransformerImpl");
    assertTrue(name.contains("@Component(\"\")\npublic final class NameTransformerImpl"), name);
    assertFalse(name.contains("ApplicationScoped"), name);
  }
}
