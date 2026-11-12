package io.github.eschizoid.telescope.jpa;

import io.github.eschizoid.telescope.conversion.ContainerCopy;
import java.util.ServiceLoader;

/**
 * The container policy this module registers: a same-typed container the persistence provider
 * reports as not loaded is shared rather than copied, so mapping an entity never loads a lazy
 * collection, and never throws for one outside its session. A loaded container is copied as core
 * copies it. Every mapper and every generated bridge copies through {@link ContainerCopy}, so the
 * runtime and generated paths take this policy alike.
 *
 * <p>A shared container is the provider's own. Reading it after its session has closed throws, as
 * reading the entity's property would: the policy changes when a collection loads, not whether
 * reading it can fail.
 *
 * <p>Active only where {@code jakarta.persistence} is on the class path; elsewhere it is passed
 * over and the standard policy applies.
 *
 * <p>Public only because {@link ServiceLoader} instantiates it; not called directly.
 */
public final class LoadedContainers implements ContainerCopy.Policy {

  /** Called by {@link ServiceLoader}. */
  public LoadedContainers() {}

  @Override
  public Object copy(final Object source, final Class<?> declared) {
    return LoadStates.loaded(source) ? ContainerCopy.standardCopy(source, declared) : source;
  }

  @Override
  public boolean active() {
    return LoadStates.AVAILABLE;
  }
}
