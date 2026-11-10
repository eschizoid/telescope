package io.github.telescopeorganization.telescope.spring.blueprint;

import io.github.telescopeorganization.telescope.Telescope;
import io.github.telescopeorganization.telescope.annotations.TelescopeTransformer;
import io.github.telescopeorganization.telescope.inject.TelescopeTransformation;
import io.github.telescopeorganization.telescope.inject.Transformation;
import java.util.List;

@TelescopeTransformer
public interface DirectoryUsersTransformer extends TelescopeTransformation<Directory, List<User>> {
  default Telescope<Directory, List<User>> path() {
    return Telescope.of(Directory.class).field(Directory::users);
  }

  @Override
  default Transformation<List<User>> transform() {
    return new Transformation<>(List.of(), users -> List.copyOf(users.reversed()));
  }
}
