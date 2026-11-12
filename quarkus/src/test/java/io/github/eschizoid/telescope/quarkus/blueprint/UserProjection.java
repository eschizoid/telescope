package io.github.eschizoid.telescope.quarkus.blueprint;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;
import io.github.eschizoid.telescope.inject.TelescopeProjection;

/** Maps through the bridge {@link User}'s {@code @Bridge} field declares. */
@TelescopeMapper("userProjection")
public interface UserProjection extends TelescopeProjection<User, UserDto> {}
