package io.github.eschizoid.telescope.spring.invalidblueprint;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.spring.TelescopeTransform;
import io.github.eschizoid.telescope.spring.TelescopeTransformation;
import io.github.eschizoid.telescope.spring.Transformation;
import java.util.Locale;

@TelescopeTransform
public interface NullPathTransformer extends TelescopeTransformation<String, String> {
  default Telescope<String, String> path() {
    return null;
  }

  @Override
  default Transformation<String> transform() {
    return new Transformation<>(null, value -> value.toLowerCase(Locale.ROOT));
  }
}
