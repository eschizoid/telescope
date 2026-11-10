package io.github.telescopeorganization.telescope.spring.invalidblueprint;

import io.github.telescopeorganization.telescope.Telescope;
import io.github.telescopeorganization.telescope.annotations.TelescopeTransformer;
import io.github.telescopeorganization.telescope.inject.TelescopeTransformation;
import io.github.telescopeorganization.telescope.inject.Transformation;

@TelescopeTransformer
public interface NullTransformer extends TelescopeTransformation<String, String> {
  @Override
  default Telescope<String, String> path() {
    return Telescope.of(String.class);
  }

  @Override
  default Transformation<String> transform() {
    return null;
  }
}
