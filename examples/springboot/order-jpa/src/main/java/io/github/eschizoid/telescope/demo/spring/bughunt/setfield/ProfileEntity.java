package io.github.eschizoid.telescope.demo.spring.bughunt.setfield;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Bean twin of {@link Profile}. Same shape, mutable, no-arg constructor + setters — deep-mapping
 * will write through the setters under {@code writeBeans(SETTERS)}. Internal fields are seeded with
 * {@link LinkedHashSet} so iteration order is observable for tests.
 *
 * <p>{@code @BeanFocus} intentionally omitted — see {@link Profile} for the rationale.
 */
public class ProfileEntity {

  private String userId;
  private Set<String> tags = new LinkedHashSet<>();
  private Set<PermissionEntity> permissions = new LinkedHashSet<>();

  public ProfileEntity() {}

  public String getUserId() {
    return userId;
  }

  public void setUserId(final String userId) {
    this.userId = userId;
  }

  public Set<String> getTags() {
    return tags;
  }

  public void setTags(final Set<String> tags) {
    this.tags = tags;
  }

  public Set<PermissionEntity> getPermissions() {
    return permissions;
  }

  public void setPermissions(final Set<PermissionEntity> permissions) {
    this.permissions = permissions;
  }
}
