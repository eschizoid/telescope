package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.spring.TelescopeMapper;

@TelescopeMapper(from = User.class, to = UserDto.class)
public interface UserProjection {
  UserDto map(User user);
}
