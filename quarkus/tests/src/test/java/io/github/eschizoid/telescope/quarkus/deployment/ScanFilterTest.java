package io.github.eschizoid.telescope.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.openapi.api.OpenApiConfig;
import io.smallrye.openapi.runtime.scanner.FilteredIndexView;
import java.util.List;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which classes the application's own OpenAPI scan reads, by its include and exclude settings. */
class ScanFilterTest {

  private static ScanFilter filter(final String... keysAndValues) {
    return new ScanFilter(config(keysAndValues));
  }

  private static SmallRyeConfig config(final String... keysAndValues) {
    final var builder = new SmallRyeConfigBuilder();
    for (var i = 0; i < keysAndValues.length; i += 2) builder.withDefaultValue(keysAndValues[i], keysAndValues[i + 1]);
    return builder.build();
  }

  private static boolean reads(final ScanFilter filter, final String name) {
    return filter.reads(DotName.createSimple(name));
  }

  @Test
  @DisplayName("with nothing configured every class is read")
  void everyClassIsReadByDefault() {
    assertThat(reads(filter(), "com.example.api.Orders")).isTrue();
  }

  @Test
  @DisplayName("an exclusion by name, by suffix or by pattern leaves a class out, and a package exclusion its package")
  void exclusionsLeaveClassesOut() {
    final var filter = filter(
      "mp.openapi.scan.exclude.classes",
      "com.example.api.Hidden,Legacy,^com\\.example\\.internal\\..*Probe$",
      "mp.openapi.scan.exclude.packages",
      "com.example.admin"
    );
    assertThat(reads(filter, "com.example.api.Hidden")).isFalse();
    assertThat(reads(filter, "com.example.old.Legacy")).isFalse();
    assertThat(reads(filter, "com.example.internal.HealthProbe")).isFalse();
    assertThat(reads(filter, "com.example.admin.users.Users")).isFalse();
    assertThat(reads(filter, "com.example.api.NotLegacyAtAll")).isTrue();
    assertThat(reads(filter, "com.example.api.Orders")).isTrue();
  }

  @Test
  @DisplayName("once any inclusion is configured only an included class is read, and the longer match decides")
  void inclusionsNarrowTheScan() {
    final var filter = filter(
      "mp.openapi.scan.classes",
      "com.example.admin.Audit",
      "mp.openapi.scan.packages",
      "com.example.api,^com\\.example\\.v[0-9]+$",
      "mp.openapi.scan.exclude.packages",
      "com.example.api.internal"
    );
    assertThat(reads(filter, "com.example.api.Orders")).isTrue();
    assertThat(reads(filter, "com.example.v2.Orders")).isTrue();
    assertThat(reads(filter, "com.example.admin.Audit")).isTrue();
    assertThat(reads(filter, "com.example.admin.Users")).isFalse();
    assertThat(reads(filter, "com.example.api.internal.Secrets")).isFalse();
  }

  @Test
  @DisplayName("a class named exactly in both lists is excluded")
  void anExactExclusionWinsOverAnExactInclusion() {
    final var filter = filter(
      "mp.openapi.scan.classes",
      "com.example.api.Orders",
      "mp.openapi.scan.exclude.classes",
      "com.example.api.Orders"
    );
    assertThat(reads(filter, "com.example.api.Orders")).isFalse();
  }

  @Test
  @DisplayName("every class is decided as SmallRye's own filtered index decides it, the rule compared case by case")
  void theRuleIsSmallRyes() {
    final var settings = config(
      "mp.openapi.scan.classes",
      "com.example.admin.Audit,Report,^.*Probe$",
      "mp.openapi.scan.packages",
      "com.example.api,^com\\.example\\.v[0-9]+$",
      "mp.openapi.scan.exclude.classes",
      "com.example.api.Hidden,Legacy,AdminReport",
      "mp.openapi.scan.exclude.packages",
      "com.example.api.internal,com.example.v2"
    );
    final var ours = new ScanFilter(settings);
    final var theirs = new FilteredIndexView(new Indexer().complete(), OpenApiConfig.fromConfig(settings));
    for (final var name : List.of(
      "com.example.api.Orders",
      "com.example.api.Hidden",
      "com.example.api.internal.Secrets",
      "com.example.api.internal.HealthProbe",
      "com.example.v2.Orders",
      "com.example.v3.Orders",
      "com.example.admin.Audit",
      "com.example.admin.Users",
      "com.example.admin.Report",
      "com.example.admin.AdminReport",
      "com.example.old.Legacy",
      "com.example.old.NotLegacy",
      "Unpackaged"
    ))
      assertThat(ours.reads(DotName.createSimple(name))).as(name).isEqualTo(theirs.accepts(DotName.createSimple(name)));
  }
}
