package io.github.eschizoid.telescope.conversion;

import io.github.eschizoid.telescope.Telescope;

/**
 * Intermediate of {@link Telescope#from(Class)} — call {@link #to(Class)} to bind the target type
 * and continue into {@link To#using(java.util.function.Function, java.util.function.Function)}.
 */
public final class From<A> {

  public From() {}

  public <B> To<A, B> to(final Class<B> target) {
    return new To<>();
  }
}
