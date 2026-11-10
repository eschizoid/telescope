package io.github.telescopeorganization.telescope.spring.web.fixtures.rest;

import io.github.telescopeorganization.telescope.annotations.TelescopeMapper;
import io.github.telescopeorganization.telescope.conversion.MapperBuilder;
import io.github.telescopeorganization.telescope.inject.TelescopeProjection;
import io.github.telescopeorganization.telescope.spring.web.fixtures.UserDto;
import io.github.telescopeorganization.telescope.spring.web.fixtures.UserRestDto;

@TelescopeMapper
public interface UserRestDtoProjection extends TelescopeProjection<UserDto, UserRestDto> {
  @Override
  default void translate(final MapperBuilder<UserDto, UserRestDto> mapping) {
    mapping.from(UserDto::fullName).to(UserRestDto::name);
  }
}
