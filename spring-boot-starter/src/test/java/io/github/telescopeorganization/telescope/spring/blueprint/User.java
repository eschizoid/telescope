package io.github.telescopeorganization.telescope.spring.blueprint;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.toOrElse;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

public record User(String name, Address address) {
  @Bridge
  public static final Mapper<User, UserDto> DTO = mapper(
    User.class,
    UserDto.class,
    toOrElse(User::name, UserDto::name, "(unnamed)")
  );

  public record Address(String city) {}
}
