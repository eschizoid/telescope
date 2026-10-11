package io.github.eschizoid.telescope.spring.web.fixtures.plain;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;
import io.github.eschizoid.telescope.conversion.MapperBuilder;
import io.github.eschizoid.telescope.inject.TelescopeProjection;
import io.github.eschizoid.telescope.spring.web.fixtures.User;
import io.github.eschizoid.telescope.spring.web.fixtures.UserDto;

@TelescopeMapper
public interface PlainUserDtoProjection extends TelescopeProjection<User, UserDto> {
  @Override
  default void translate(final MapperBuilder<User, UserDto> mapping) {
    mapping.from(User::name).to(UserDto::fullName);
  }
}
