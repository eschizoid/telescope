package io.github.eschizoid.telescope.spring.web.fixtures.upper;

import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;
import io.github.eschizoid.telescope.conversion.MapperBuilder;
import io.github.eschizoid.telescope.inject.TelescopeProjection;
import io.github.eschizoid.telescope.spring.web.fixtures.User;
import io.github.eschizoid.telescope.spring.web.fixtures.UserDto;

@TelescopeMapper
public interface UpperUserDtoProjection extends TelescopeProjection<User, UserDto> {
  @Override
  default void translate(final MapperBuilder<User, UserDto> mapping) {
    mapping.add(to(User::name, UserDto::fullName, String::toUpperCase, String::toLowerCase));
  }
}
