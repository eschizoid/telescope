package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.conversion.MapperBuilder;
import io.github.eschizoid.telescope.inject.TelescopeProjection;
import io.github.eschizoid.telescope.spring.TelescopeMapper;

/**
 * Overrides translate with no rows, so the projection maps through a core Mapper, not UserBridge.
 */
@TelescopeMapper
public interface UserTranslatedProjection extends TelescopeProjection<User, UserDto> {
  @Override
  default void translate(final MapperBuilder<User, UserDto> mapping) {}
}
