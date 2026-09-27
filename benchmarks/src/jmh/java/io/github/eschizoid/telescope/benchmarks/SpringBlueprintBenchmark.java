package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.spring.TelescopeCustomizer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.ResolvableType;

/** Measures the Spring interface wrapper against the same generated bridge and cached path. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class SpringBlueprintBenchmark {

  private final SpringBlueprintSource source = new SpringBlueprintSource(
    "Alice",
    new SpringBlueprintSource.Address("Boston")
  );
  private final SpringBlueprintMapper mapper = new SpringBlueprintMapperImpl();
  private final SpringBlueprintCityTransformer city = new SpringBlueprintCityTransformerImpl();
  private final SpringBlueprintCityPrefixTransformer prefix = new SpringBlueprintCityPrefixTransformerImpl();
  // No TelescopeCustomizer beans: the configured mapper runs exactly its declared transformers.
  private final ObjectProvider<TelescopeCustomizer<SpringBlueprintConfiguredMapper>> noCustomizers =
    new StaticListableBeanFactory().getBeanProvider(
      ResolvableType.forClassWithGenerics(TelescopeCustomizer.class, SpringBlueprintConfiguredMapper.class)
    );
  private final SpringBlueprintConfiguredMapper configuredMapper = new SpringBlueprintConfiguredMapperImpl(
    prefix,
    city,
    noCustomizers
  );
  private final Telescope<SpringBlueprintSource, String> manualPath = Telescope.of(SpringBlueprintSource.class)
    .field(SpringBlueprintSource::address)
    .field(SpringBlueprintSource.Address::city);

  @Benchmark
  public SpringBlueprintTarget bridge() {
    return SpringBlueprintSourceBridge.forward(source);
  }

  @Benchmark
  public SpringBlueprintTarget springMapper() {
    return mapper.map(source);
  }

  @Benchmark
  public SpringBlueprintTarget manualTransformedBridge() {
    final var withPrefix = manualPath.update(source, value -> "PREFIX:" + value);
    final var normalized = manualPath.update(withPrefix, value -> value.toLowerCase(Locale.ROOT));
    return SpringBlueprintSourceBridge.forward(normalized);
  }

  @Benchmark
  public SpringBlueprintTarget configuredSpringMapper() {
    return configuredMapper.map(source);
  }

  @Benchmark
  public SpringBlueprintSource manualPath() {
    return manualPath.update(source, value -> value.toLowerCase(Locale.ROOT));
  }

  @Benchmark
  public SpringBlueprintSource springTransform() {
    return city.apply(source);
  }
}
