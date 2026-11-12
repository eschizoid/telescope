package io.github.eschizoid.telescope.quarkus.blueprint;

import io.github.eschizoid.telescope.annotations.TelescopeMapper;
import io.github.eschizoid.telescope.conversion.MapperBuilder;
import io.github.eschizoid.telescope.inject.TelescopeProjection;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Overrides translate with no rows, so the projection maps through a core Mapper, not the bridge
 * {@link User}'s {@code @Bridge} field declares. Counts how often its rows are read.
 */
@TelescopeMapper
public interface UserTranslatedProjection extends TelescopeProjection<User, UserDto> {
  AtomicInteger TRANSLATIONS = new AtomicInteger();

  @Override
  default void translate(final MapperBuilder<User, UserDto> mapping) {
    TRANSLATIONS.incrementAndGet();
  }
}
