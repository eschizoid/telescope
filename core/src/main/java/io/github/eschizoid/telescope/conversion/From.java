package io.github.eschizoid.telescope.conversion;

import io.github.eschizoid.telescope.Telescope;

/**
 * Intermediate of {@link Telescope#from(Class)} — call {@link #to(Class)} to bind the target type
 * and continue into {@link To#using(java.util.function.Function, java.util.function.Function)}.
 *
 * <p>External code never constructs this directly; the only entry point is {@link
 * Telescope#from(Class)}. The no-arg constructor is declared {@code public} solely because the
 * factory lives in a different package ({@code io.github.eschizoid.telescope}). Treat it as
 * module-internal — it may be made package-private in a future release once the factory and this
 * type can share a package.
 */
public final class From<A> {

  /**
   * <b>Module-internal seam — NOT public API.</b> Use {@link Telescope#from(Class)} instead. This
   * constructor is declared {@code public} only so the cross-package factory can call it.
   */
  public From() {}

  public <B> To<A, B> to(final Class<B> target) {
    return new To<>();
  }
}
