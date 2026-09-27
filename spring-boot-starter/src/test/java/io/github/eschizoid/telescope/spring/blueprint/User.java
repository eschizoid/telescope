package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Default;

@Bridge(value = UserDto.class, defaults = @Default(field = "name", value = "(unnamed)"))
public record User(String name, Address address) {
  public record Address(String city) {}
}
