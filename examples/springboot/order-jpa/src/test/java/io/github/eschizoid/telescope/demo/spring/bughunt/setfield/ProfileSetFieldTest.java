package io.github.eschizoid.telescope.demo.spring.bughunt.setfield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@code Set<E>} navigation end-to-end:
 *
 * <ul>
 *   <li>{@code Mapper<Profile, ProfileEntity>} round-trip with same-typed {@code Set<String>} +
 *       sub-mapped {@code Set<Permission>↔Set<PermissionEntity>} (auto-lift via {@code
 *       Iso.liftSet}).
 *   <li>{@code Telescope.of(Profile.class).setField(Profile::tags).each().update(...)} — the typed
 *       {@code SetPath} navigation surface.
 *   <li>{@link LinkedHashSet}-style order preservation through the lifted Iso.
 * </ul>
 */
class ProfileSetFieldTest {

  private final ProfileMappers config = new ProfileMappers();
  private final Mapper<Permission, PermissionEntity> permissionMapper = config.permissionMapper();
  private final Mapper<Profile, ProfileEntity> profileMapper = config.profileMapper(permissionMapper);

  private static Profile sample() {
    final var tags = new LinkedHashSet<>(List.of("alpha", "beta", "gamma"));
    final var perms = new LinkedHashSet<>(List.of(new Permission("orders", "read"), new Permission("orders", "write")));
    return new Profile("user-42", tags, perms);
  }

  @Test
  void mapperRoundTripPreservesSetShapeAndOrder() {
    final var profile = sample();

    final var entity = profileMapper.forward(profile);
    assertThat(entity.getUserId()).isEqualTo("user-42");
    assertThat(entity.getTags()).containsExactly("alpha", "beta", "gamma");
    assertThat(entity.getPermissions()).extracting(PermissionEntity::getResource).containsExactly("orders", "orders");
    assertThat(entity.getPermissions()).extracting(PermissionEntity::getAction).containsExactly("read", "write");

    final var back = profileMapper.backward(entity);
    assertThat(back).isEqualTo(profile);
    assertThat(back.tags()).containsExactly("alpha", "beta", "gamma");
  }

  @Test
  void setFieldEachUpdatesEveryTagInPlace() {
    final var profile = sample();

    final var upper = Telescope.of(Profile.class).setField(Profile::tags).each().update(profile, String::toUpperCase);

    assertThat(upper.tags()).containsExactly("ALPHA", "BETA", "GAMMA");
    // userId + permissions untouched
    assertThat(upper.userId()).isEqualTo("user-42");
    assertThat(upper.permissions()).isEqualTo(profile.permissions());
  }

  @Test
  void liftSetTolaratesEmptyAndNullSets() {
    // Empty Set<String> tags + empty Set<Permission> permissions — the lifted Iso must round-trip
    // both edges without NPE or shape drift.
    final var emptyProfile = new Profile("user-empty", Set.of(), Set.of());

    final var entity = profileMapper.forward(emptyProfile);
    assertThat(entity.getTags()).isEmpty();
    assertThat(entity.getPermissions()).isEmpty();

    final var back = profileMapper.backward(entity);
    assertThat(back).isEqualTo(emptyProfile);
  }
}
