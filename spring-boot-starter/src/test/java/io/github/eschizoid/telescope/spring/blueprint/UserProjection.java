package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;
import io.github.eschizoid.telescope.inject.TelescopeProjection;

@TelescopeMapper("userProjection")
public interface UserProjection extends TelescopeProjection<User, UserDto> {}
