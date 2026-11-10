package io.github.telescopeorganization.telescope.spring.web.fixtures.upper;

import static io.github.telescopeorganization.telescope.mapping.Mapping.to;

import io.github.telescopeorganization.telescope.annotations.TelescopeMapper;
import io.github.telescopeorganization.telescope.conversion.MapperBuilder;
import io.github.telescopeorganization.telescope.inject.TelescopeProjection;
import io.github.telescopeorganization.telescope.spring.web.fixtures.User;
import io.github.telescopeorganization.telescope.spring.web.fixtures.UserDto;

@TelescopeMapper
public interface UpperUserDtoProjection extends TelescopeProjection<User, UserDto> {
  @Override
  default void translate(final MapperBuilder<User, UserDto> mapping) {
    mapping.add(to(User::name, UserDto::fullName, String::toUpperCase, String::toLowerCase));
  }
}
