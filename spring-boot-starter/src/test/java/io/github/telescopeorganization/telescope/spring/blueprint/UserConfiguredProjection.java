package io.github.telescopeorganization.telescope.spring.blueprint;

import io.github.telescopeorganization.telescope.annotations.TelescopeMapper;
import io.github.telescopeorganization.telescope.inject.TelescopeProjection;

@TelescopeMapper(transformers = { UserCityPrefixTransformer.class, UserCityTransformer.class })
public interface UserConfiguredProjection extends TelescopeProjection<User, UserDto> {}
