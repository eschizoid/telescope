package io.github.eschizoid.telescope.demo.spring.bughunt.setfield;

import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@code Mapper<Profile, ProfileEntity>} — the {@code Set<E>}-shaped slice. Two rows:
 *
 * <ul>
 *   <li>{@code tags} ({@code Set<String>} ↔ {@code Set<String>}) — auto-inferred same-name +
 *       same-type pair. No mapping row needed; the deep-map factory uses identity at the element
 *       level and lifts trivially through {@link
 *       io.github.eschizoid.telescope.internal.optics.Iso#liftSet}.
 *   <li>{@code permissions} ({@code Set<Permission>} ↔ {@code Set<PermissionEntity>}) — element
 *       types differ, so the row drops in a pre-built {@link #permissionMapper()}. Telescope sees
 *       the matching {@code Set<...>} container shape on both accessors and lifts the element
 *       mapper into a {@code Mapper<Set<Permission>, Set<PermissionEntity>>} automatically.
 * </ul>
 */
@Configuration
public class ProfileMappers {

  @Bean
  public Mapper<Permission, PermissionEntity> permissionMapper() {
    return Telescope.mapper(Permission.class, PermissionEntity.class, writeBeans(SETTERS));
  }

  @Bean
  public Mapper<Profile, ProfileEntity> profileMapper(final Mapper<Permission, PermissionEntity> permissionMapper) {
    return Telescope.mapper(
      Profile.class,
      ProfileEntity.class,
      via(Profile::permissions, ProfileEntity::getPermissions, permissionMapper),
      writeBeans(SETTERS)
    );
  }
}
