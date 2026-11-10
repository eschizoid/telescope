package io.github.telescopeorganization.telescope.spring.invalidblueprint;

import io.github.telescopeorganization.telescope.Telescope;
import io.github.telescopeorganization.telescope.annotations.TelescopeTransformer;
import io.github.telescopeorganization.telescope.inject.TelescopeTransformation;
import io.github.telescopeorganization.telescope.inject.Transformation;
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
