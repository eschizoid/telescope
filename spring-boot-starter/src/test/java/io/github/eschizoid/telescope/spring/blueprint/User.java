package io.github.eschizoid.telescope.spring.blueprint;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.toOrElse;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

public record User(String name, Address address) {
  @Bridge
  public static final Mapper<User, UserDto> DTO = mapper(
    User.class,
    UserDto.class,
    toOrElse(User::name, UserDto::name, "(unnamed)")
  );

  public record Address(String city) {}
}
