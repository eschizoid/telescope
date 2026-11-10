package io.github.telescopeorganization.telescope.benchmarks;

import io.github.telescopeorganization.telescope.Telescope;
import io.github.telescopeorganization.telescope.annotations.TelescopeTransformer;
import io.github.telescopeorganization.telescope.inject.TelescopeTransformation;
import io.github.telescopeorganization.telescope.inject.Transformation;
import java.util.Locale;

@TelescopeTransformer
public interface SpringBlueprintCityTransformer extends TelescopeTransformation<SpringBlueprintSource, String> {
  @Override
  default Telescope<SpringBlueprintSource, String> path() {
    return Telescope.of(SpringBlueprintSource.class)
      .field(SpringBlueprintSource::address)
      .field(SpringBlueprintSource.Address::city);
  }

  @Override
  default Transformation<String> transform() {
    return new Transformation<>(null, value -> value.toLowerCase(Locale.ROOT));
  }
}
