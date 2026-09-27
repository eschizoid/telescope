package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.spring.TelescopeTransform;
import io.github.eschizoid.telescope.spring.TelescopeTransformation;
import io.github.eschizoid.telescope.spring.Transformation;
import java.util.List;

@TelescopeTransform
public interface DirectoryUsersTransformer extends TelescopeTransformation<Directory, List<User>> {
  default Telescope<Directory, List<User>> path() {
    return Telescope.of(Directory.class).field(Directory::users);
  }

  @Override
  default Transformation<List<User>> transform() {
    return new Transformation<>(List.of(), users -> List.copyOf(users.reversed()));
  }
}
