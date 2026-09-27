package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.spring.TelescopeMapper;
import io.github.eschizoid.telescope.spring.TelescopeProjection;

@TelescopeMapper(transformers = { UserCityPrefixTransformer.class, UserCityTransformer.class })
public interface UserConfiguredProjection extends TelescopeProjection<User, UserDto> {}
