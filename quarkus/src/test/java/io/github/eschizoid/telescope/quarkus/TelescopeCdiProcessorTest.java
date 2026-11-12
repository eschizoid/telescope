package io.github.eschizoid.telescope.quarkus;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.codegen.BridgeProcessor;
import io.github.eschizoid.telescope.codegen.ProcessorHarness;
import io.github.eschizoid.telescope.codegen.TelescopeMapperProcessor;
import java.util.List;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The text {@code TelescopeMapperProcessor} writes when CDI is on the classpath, which this
 * module's test classpath has through Quarkus ArC, and the refusals that choose between CDI and
 * Spring. This module's own TelescopeProducer puts telescope-quarkus on the processor's classpath.
 * Spring is not on it; a test that needs Spring declares its annotation as a source.
 */
class TelescopeCdiProcessorTest {

  private static final JavaFileObject USER = source(
    "demo.User",
    """
    package demo;
    import io.github.eschizoid.telescope.annotations.Bridge;
    @Bridge(UserDto.class)
    public record User(String name, String city) {}
    """
  );

  private static final JavaFileObject USER_DTO = source(
    "demo.UserDto",
    "package demo; public record UserDto(String name, String city) {}"
  );

  private static final JavaFileObject PROJECTION = source(
    "demo.UserProjection",
    """
    package demo;
    import io.github.eschizoid.telescope.annotations.TelescopeMapper;
    import io.github.eschizoid.telescope.inject.TelescopeProjection;
    @TelescopeMapper(transformers = CityTransformer.class)
    public interface UserProjection extends TelescopeProjection<User, UserDto> {}
    """
  );

  private static final JavaFileObject TRANSLATED = source(
    "demo.TranslatedProjection",
    """
    package demo;
    import io.github.eschizoid.telescope.annotations.TelescopeMapper;
    import io.github.eschizoid.telescope.conversion.MapperBuilder;
    import io.github.eschizoid.telescope.inject.TelescopeProjection;
    @TelescopeMapper("translated")
    public interface TranslatedProjection extends TelescopeProjection<User, UserDto> {
      @Override default void translate(final MapperBuilder<User, UserDto> mapping) {}
    }
    """
  );

  private static final JavaFileObject PLAIN = source(
    "demo.UserMapper",
    """
    package demo;
    import io.github.eschizoid.telescope.annotations.TelescopeMapper;
    @TelescopeMapper(from = User.class, to = UserDto.class)
    public interface UserMapper { UserDto map(User user); }
    """
  );

  private static final JavaFileObject TRANSFORMER = source(
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
  );

  /** Spring's component annotation, which is all the processor looks for to find Spring. */
  private static final JavaFileObject SPRING_COMPONENT = source(
    "org.springframework.stereotype.Component",
    "package org.springframework.stereotype; public @interface Component { String value() default \"\"; }"
  );

  private static ProcessorHarness.Compilation compileAll(final List<String> options, final JavaFileObject... sources) {
    return ProcessorHarness.compileFully(
      List.of(new TelescopeMapperProcessor(), new BridgeProcessor()),
      options,
      sources
    );
  }

  @Test
  @DisplayName("a projection is an @ApplicationScoped bean that collects its customizers through @All")
  void projectionIsAnApplicationScopedBean() {
    final var result = compileAll(List.of(), USER, USER_DTO, PROJECTION, TRANSFORMER);
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
    assertThat(result.warningsInGenerated()).isEmpty();
    assertThat(result.generated().get("demo.UserProjectionImpl"))
      .contains("@ApplicationScoped\npublic class UserProjectionImpl implements UserProjection {")
      .contains("  protected UserProjectionImpl() {\n    this.transformers = null;\n  }")
      .contains(
        "  @Inject\n  @SuppressWarnings(\"this-escape\")\n  public UserProjectionImpl(final CityTransformer transformer0," +
          " @All final List<TelescopeCustomizer<UserProjection>> customizers) {"
      )
      .contains("    customizers.forEach(customizer -> customizer.customize(this));")
      .contains("return UserBridge.forward(value);")
      .doesNotContain("@Named")
      .doesNotContain("ObjectProvider")
      .doesNotContain("final class");
  }

  @Test
  @DisplayName("a projection over a core Mapper builds the mapper in its injecting constructor, not for the proxy")
  void translatedProjectionBuildsItsMapperInTheInjectingConstructor() {
    final var result = compileAll(List.of(), USER, USER_DTO, TRANSLATED);
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
    assertThat(result.warningsInGenerated()).isEmpty();
    final var generated = result.generated().get("demo.TranslatedProjectionImpl");
    assertThat(generated)
      .contains("@ApplicationScoped\n@Named(\"translated\")\npublic class TranslatedProjectionImpl")
      .contains("  private final Mapper<User, UserDto> mapping;\n")
      .contains(
        "  protected TranslatedProjectionImpl() {\n    this.mapping = null;\n    this.transformers = null;\n  }"
      )
      .doesNotContain("UserBridge");
    final var injecting = generated.substring(generated.indexOf("  @Inject"));
    assertThat(injecting.substring(0, injecting.indexOf("  }"))).contains(
      "@All final List<TelescopeCustomizer<TranslatedProjection>> customizers) {\n    this.mapping = createMapping();"
    );
  }

  @Test
  @DisplayName("a plain mapper with nothing to inject is a bean with no constructor of its own")
  void plainMapperHasNoConstructor() {
    final var result = compileAll(List.of(), USER, USER_DTO, PLAIN);
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
    assertThat(result.warningsInGenerated()).isEmpty();
    assertThat(result.generated().get("demo.UserMapperImpl"))
      .contains("@ApplicationScoped\npublic class UserMapperImpl implements UserMapper {")
      .contains("return UserBridge.forward(input);")
      .doesNotContain("@Inject")
      .doesNotContain("UserMapperImpl(");
  }

  @Test
  @DisplayName("a transformer builds its path and transformation in @PostConstruct, which the proxy never runs")
  void transformerCachesInPostConstruct() {
    final var result = compileAll(List.of(), USER, USER_DTO, TRANSFORMER);
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
    assertThat(result.warningsInGenerated()).isEmpty();
    assertThat(result.generated().get("demo.CityTransformerImpl"))
      .contains("@ApplicationScoped\n@Named(\"city\")\npublic class CityTransformerImpl implements CityTransformer {")
      .contains("  private Telescope<User, String> path;\n  private Transformation<String> transformation;\n")
      .contains(
        "  @PostConstruct\n  void $telescopeCachePathAndTransform() {\n" +
          "    path = Objects.requireNonNull(CityTransformer.super.path(), \"path() must not return null\");"
      )
      .doesNotContain("private final");
  }

  @Test
  @DisplayName("a transformer interface may declare any default method of its own beside the generated callback")
  void transformerDefaultMethodDoesNotClashWithTheCallback() {
    final var transformer = source(
      "demo.CacheTransformer",
      """
      package demo;
      import io.github.eschizoid.telescope.Telescope;
      import io.github.eschizoid.telescope.annotations.TelescopeTransformer;
      import io.github.eschizoid.telescope.inject.TelescopeTransformation;
      import io.github.eschizoid.telescope.inject.Transformation;
      @TelescopeTransformer
      public interface CacheTransformer extends TelescopeTransformation<User, String> {
        @Override default Telescope<User, String> path() { return Telescope.of(User.class).field(User::city); }
        @Override default Transformation<String> transform() { return new Transformation<>(null, String::strip); }
        default void cachePathAndTransform() {}
      }
      """
    );
    final var result = compileAll(List.of(), USER, USER_DTO, transformer);
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
    assertThat(result.warningsInGenerated()).isEmpty();
  }

  @Test
  @DisplayName("with telescope-quarkus on the classpath, a Spring annotation beside it still gives a CDI bean")
  void quarkusStarterGeneratesCdiBesideTheSpringAnnotations() {
    final var result = ProcessorHarness.compile(
      List.of(new TelescopeMapperProcessor()),
      SPRING_COMPONENT,
      USER,
      USER_DTO,
      TRANSFORMER
    );
    assertThat(result.success()).withFailMessage(result.errorMessages()).isTrue();
    assertThat(result.generated().get("demo.CityTransformerImpl"))
      .contains("@ApplicationScoped")
      .doesNotContain("Component");
  }

  @Test
  @DisplayName("telescope.beans overrides the starter, with Spring and CDI both on the classpath")
  void optionChoosesBetweenBothContainers() {
    final var cdi = ProcessorHarness.compile(
      List.of(new TelescopeMapperProcessor()),
      List.of("-Atelescope.beans=cdi"),
      SPRING_COMPONENT,
      USER,
      USER_DTO,
      TRANSFORMER
    );
    assertThat(cdi.success()).withFailMessage(cdi.errorMessages()).isTrue();
    assertThat(cdi.generated().get("demo.CityTransformerImpl"))
      .contains("@ApplicationScoped")
      .doesNotContain("Component");
    final var spring = ProcessorHarness.compile(
      List.of(new TelescopeMapperProcessor()),
      List.of("-Atelescope.beans=spring"),
      SPRING_COMPONENT,
      USER,
      USER_DTO,
      TRANSFORMER
    );
    assertThat(spring.success()).withFailMessage(spring.errorMessages()).isTrue();
    assertThat(spring.generated().get("demo.CityTransformerImpl"))
      .contains("@Component(\"city\")\npublic final class CityTransformerImpl")
      .doesNotContain("ApplicationScoped");
  }
}
