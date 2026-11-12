package io.github.eschizoid.telescope.quarkus.blueprint;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;

/** A plain mapper interface, which maps through the generated bridge. */
@TelescopeMapper(from = User.class, to = UserDto.class)
public interface UserMapper {
  UserDto map(User user);
}
