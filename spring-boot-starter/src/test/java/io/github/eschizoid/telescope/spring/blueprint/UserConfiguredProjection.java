package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.inject.TelescopeProjection;
import io.github.eschizoid.telescope.spring.TelescopeMapper;

@TelescopeMapper(transformers = { UserCityPrefixTransformer.class, UserCityTransformer.class })
public interface UserConfiguredProjection extends TelescopeProjection<User, UserDto> {}
