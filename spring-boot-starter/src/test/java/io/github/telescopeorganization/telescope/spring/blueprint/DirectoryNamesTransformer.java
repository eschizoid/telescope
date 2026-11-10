package io.github.telescopeorganization.telescope.spring.blueprint;

import io.github.telescopeorganization.telescope.Telescope;
import io.github.telescopeorganization.telescope.annotations.TelescopeTransformer;
import io.github.telescopeorganization.telescope.inject.TelescopeTransformation;
import io.github.telescopeorganization.telescope.inject.Transformation;
import java.util.Locale;

@TelescopeTransformer
public interface DirectoryNamesTransformer extends TelescopeTransformation<Directory, String> {
  default Telescope<Directory, String> path() {
    return Telescope.of(Directory.class).each(Directory::users).field(User::name);
  }

  @Override
  default Transformation<String> transform() {
    return new Transformation<>("(unnamed)", value -> value.toLowerCase(Locale.ROOT));
  }
}
