package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.inject.TelescopeProjection;
import io.github.eschizoid.telescope.spring.TelescopeMapper;

@TelescopeMapper("userProjection")
public interface UserProjection extends TelescopeProjection<User, UserDto> {}
