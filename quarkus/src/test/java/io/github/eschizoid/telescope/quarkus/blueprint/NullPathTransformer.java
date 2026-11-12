package io.github.eschizoid.telescope.quarkus.blueprint;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.TelescopeTransformer;
import io.github.eschizoid.telescope.inject.TelescopeTransformation;
import io.github.eschizoid.telescope.inject.Transformation;

@TelescopeTransformer
public interface NullPathTransformer extends TelescopeTransformation<User, String> {
  @Override
  default Telescope<User, String> path() {
    return null;
  }

  @Override
  default Transformation<String> transform() {
    return new Transformation<>(null, value -> value);
  }
}
