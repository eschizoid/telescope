package io.github.telescopeorganization.telescope.spring.blueprint;

import io.github.telescopeorganization.telescope.annotations.TelescopeMapper;
import io.github.telescopeorganization.telescope.conversion.MapperBuilder;
import io.github.telescopeorganization.telescope.inject.TelescopeProjection;

/**
 * Overrides translate with no rows, so the projection maps through a core Mapper, not the bridge
 * {@link User}'s {@code @Bridge} field declares.
 */
@TelescopeMapper
public interface UserTranslatedProjection extends TelescopeProjection<User, UserDto> {
  @Override
  default void translate(final MapperBuilder<User, UserDto> mapping) {}
}
