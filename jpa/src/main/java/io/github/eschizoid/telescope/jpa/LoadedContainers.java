package io.github.eschizoid.telescope.jpa;

import io.github.eschizoid.telescope.conversion.ContainerCopy;
import java.util.ServiceLoader;

/**
 * The container policy this module registers. A same-typed container the persistence provider
 * reports as not loaded is shared rather than copied, so mapping an entity never loads a lazy
 * collection, and never throws for one outside its session; a loaded container is copied as core
 * copies it. A collection of entities {@code into} or {@code patchInto} writes into one the target
 * holds is merged by identifier: a converted element matching a held one by identifier is written
 * into it in place, a new one is added and a held one nothing matches is removed, so a row that did
 * not change is not written. The back-reference of a collection's elements is the property
 * {@code @OneToMany(mappedBy)} names on it, which a write sets to the owner where no adder does and
 * clears on an element it takes out where the owner has no remover. Every mapper and every
 * generated bridge copies and writes through {@link ContainerCopy}, so the runtime and generated
 * paths take this policy alike.
 *
 * <p>A shared container is the provider's own. Reading it after its session has closed throws, as
 * reading the entity's property would: the policy changes when a collection loads, not whether
 * reading it can fail.
 *
 * <p>The merge needs elements converted by a conversion that can write one element into another in
 * place: a pair the mapper converts property by property, or the mapper of a {@code via} row.
 * Elements converted by a class or methods written by hand are written as core writes them, cleared
 * and refilled.
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
  public void refill(final ContainerCopy.Refill refill) {
    IdMerge.refill(refill);
  }

  @Override
  public Object check(final ContainerCopy.Refill refill) {
    return IdMerge.check(refill);
  }

  @Override
  public String backReference(final Class<?> owner, final String property) {
    return MappedBy.of(owner, property);
  }

  @Override
  public boolean active() {
    return LoadStates.AVAILABLE;
  }
}
