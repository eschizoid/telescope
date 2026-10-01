package io.github.eschizoid.telescope.spring.invalidblueprint;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.TelescopeTransformer;
import io.github.eschizoid.telescope.inject.TelescopeTransformation;
import io.github.eschizoid.telescope.inject.Transformation;
import java.util.Locale;

@TelescopeTransformer
public interface NullPathTransformer extends TelescopeTransformation<String, String> {
  default Telescope<String, String> path() {
    return null;
  }

  @Override
  default Transformation<String> transform() {
    return new Transformation<>(null, value -> value.toLowerCase(Locale.ROOT));
  }
}
