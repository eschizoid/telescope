package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;
import io.github.eschizoid.telescope.inject.TelescopeProjection;

@TelescopeMapper(transformers = { UserCityPrefixTransformer.class, UserCityTransformer.class })
public interface UserConfiguredProjection extends TelescopeProjection<User, UserDto> {}
