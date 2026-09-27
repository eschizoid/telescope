package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.spring.TelescopeMapper;
import io.github.eschizoid.telescope.spring.TelescopeProjection;

@TelescopeMapper("userProjection")
public interface UserProjection extends TelescopeProjection<User, UserDto> {}
