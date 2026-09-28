package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.TelescopeTransformer;
import io.github.eschizoid.telescope.inject.TelescopeTransformation;
import io.github.eschizoid.telescope.inject.Transformation;

@TelescopeTransformer
public interface SpringBlueprintCityPrefixTransformer extends TelescopeTransformation<SpringBlueprintSource, String> {
  @Override
  default Telescope<SpringBlueprintSource, String> path() {
    return Telescope.of(SpringBlueprintSource.class)
      .field(SpringBlueprintSource::address)
      .field(SpringBlueprintSource.Address::city);
  }

  @Override
  default Transformation<String> transform() {
    return new Transformation<>("PREFIX:UNKNOWN", value -> "PREFIX:" + value);
  }
}
