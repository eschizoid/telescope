package io.github.eschizoid.telescope.quarkus.blueprint;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;

/** A plain mapper interface that runs a declared transformer before the generated bridge. */
@TelescopeMapper(from = User.class, to = UserDto.class, transformers = UserCityTransformer.class)
public interface LowerCityUserMapper {
  UserDto map(User user);
}
