package io.github.eschizoid.telescope.internal.pairing;

/**
 * A source class and the target class it is paired with, as each world keys a pair.
 *
 * @param <T> the world's type handle
 */
public interface Paired<T> {
  T source();

  T target();
}
