package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.TelescopeTransformer;
import io.github.eschizoid.telescope.inject.TelescopeTransformation;
import io.github.eschizoid.telescope.inject.Transformation;
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
