package io.github.eschizoid.telescope.spring.invalidblueprint;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.spring.TelescopeTransformation;
import io.github.eschizoid.telescope.spring.TelescopeTransformer;
import io.github.eschizoid.telescope.spring.Transformation;

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
