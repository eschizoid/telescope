package io.github.telescopeorganization.telescope.spring.blueprint;

import io.github.telescopeorganization.telescope.annotations.TelescopeMapper;
import io.github.telescopeorganization.telescope.inject.TelescopeProjection;

@TelescopeMapper("userProjection")
public interface UserProjection extends TelescopeProjection<User, UserDto> {}
