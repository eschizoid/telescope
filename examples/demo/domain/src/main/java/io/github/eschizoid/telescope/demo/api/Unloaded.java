package io.github.eschizoid.telescope.demo.api;

import jakarta.persistence.Persistence;
import jakarta.persistence.PersistenceUtil;

/**
 * A Jackson value filter that leaves out a collection the persistence provider has not loaded, so
 * writing a response never loads one. Jackson calls {@link #equals} with the property's value and
 * leaves the property out when it answers true.
 *
 * <p>A collection no provider knows, such as a plain {@code ArrayList}, counts as loaded and is
 * written.
 */
public final class Unloaded {

  private static final PersistenceUtil PERSISTENCE = Persistence.getPersistenceUtil();

  /** Called by Jackson. */
  public Unloaded() {}

  @Override
  public boolean equals(final Object value) {
    return value != null && !PERSISTENCE.isLoaded(value);
  }

  @Override
  public int hashCode() {
    return Unloaded.class.hashCode();
  }
}
