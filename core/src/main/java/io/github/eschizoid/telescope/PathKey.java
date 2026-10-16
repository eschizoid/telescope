package io.github.eschizoid.telescope;

import java.util.Objects;

/**
 * The key one direction of a generated bridge tracks its objects under on an active path: the class
 * it converts from and the class it converts into. Codegen support, not for hand-written call
 * sites.
 *
 * <p>A bridge's {@code forward} and {@code backward} key the path by identity, so on a path the
 * bridge started itself each direction of each bridge has a set of its own. A runtime mapper that
 * hands a bridge its own path reads the key by its two classes instead, so the bridge and the
 * mapper find the same objects for the same pair and cut a cycle through the bridge at the same
 * reference.
 *
 * @param from the class the objects are converted from, as the bridge declares it
 * @param into the class the objects are converted into
 */
public record PathKey(Class<?> from, Class<?> into) {
  /** Checks both classes. */
  public PathKey {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(into, "into");
  }
}
