package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.inject.TelescopeTransformation;
import io.github.eschizoid.telescope.inject.Transformation;
import io.github.eschizoid.telescope.spring.TelescopeTransformer;
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
