package io.github.eschizoid.telescope.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The configuration the wire scan reads: every key the application sets, except those that narrow
 * which classes SmallRye OpenAPI scans.
 */
class EveryClassConfigTest {

  private static final SmallRyeConfig APPLICATION = new SmallRyeConfigBuilder()
    .withDefaultValue("mp.openapi.scan.packages", "com.example.api")
    .withDefaultValue("mp.openapi.scan.exclude.classes", "com.example.api.Hidden")
    .withDefaultValue("mp.openapi.scan.disable", "false")
    .withDefaultValue("mp.openapi.extensions.smallrye.scan.profiles", "public")
    .withDefaultValue("mp.openapi.extensions.smallrye.scan.exclude.profiles", "internal")
    .withDefaultValue("mp.openapi.extensions.smallrye.property-naming-strategy", "SNAKE_CASE")
    .build();

  private final EveryClassConfig config = new EveryClassConfig(APPLICATION);

  @Test
  @DisplayName("a key that narrows the scanned classes or profiles reads as unset, however it is asked for")
  void aScanFilterReadsAsUnset() {
    assertThat(config.getOptionalValue("mp.openapi.scan.packages", String.class)).isEmpty();
    assertThat(config.getOptionalValues("mp.openapi.scan.exclude.classes", String.class)).isEmpty();
    assertThat(config.getOptionalValues("mp.openapi.extensions.smallrye.scan.profiles", String.class)).isEmpty();
    assertThat(
      config.getOptionalValues("mp.openapi.extensions.smallrye.scan.exclude.profiles", String.class)
    ).isEmpty();
    assertThatThrownBy(() -> config.getValue("mp.openapi.scan.packages", String.class)).isInstanceOf(
      NoSuchElementException.class
    );
    final var value = config.getConfigValue("mp.openapi.scan.packages");
    assertThat(value.getName()).isEqualTo("mp.openapi.scan.packages");
    assertThat(value.getValue()).isNull();
    assertThat(value.getRawValue()).isNull();
    assertThat(value.getSourceName()).isNull();
    assertThat(value.getSourceOrdinal()).isZero();
  }

  @Test
  @DisplayName("every other key, the switch that turns the scan off included, reads as the application sets it")
  void everyOtherKeyIsTheApplications() {
    assertThat(config.getValue("mp.openapi.extensions.smallrye.property-naming-strategy", String.class)).isEqualTo(
      "SNAKE_CASE"
    );
    assertThat(config.getOptionalValue("mp.openapi.scan.disable", Boolean.class)).contains(false);
    assertThat(config.getConfigValue("mp.openapi.scan.disable").getValue()).isEqualTo("false");
    final var names = new ArrayList<String>();
    config.getPropertyNames().forEach(names::add);
    assertThat(names)
      .contains("mp.openapi.scan.disable", "mp.openapi.extensions.smallrye.property-naming-strategy")
      .doesNotContain("mp.openapi.scan.packages", "mp.openapi.scan.exclude.classes");
    assertThat(config.getConfigSources()).containsExactlyElementsOf(APPLICATION.getConfigSources());
    assertThat(config.getConverter(Integer.class)).isPresent();
    assertThat(config.unwrap(SmallRyeConfig.class)).isSameAs(APPLICATION);
  }
}
