package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.spring.TelescopeTransformation;
import io.github.eschizoid.telescope.spring.TelescopeTransformer;
import io.github.eschizoid.telescope.spring.Transformation;
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
