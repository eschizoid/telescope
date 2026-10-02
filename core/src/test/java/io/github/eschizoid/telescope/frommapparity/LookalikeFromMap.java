package io.github.eschizoid.telescope.frommapparity;

import java.util.Map;

/**
 * A hand-written class named the way a generated binder for {@link Lookalike} would be, which the
 * processor did not write and which registers nothing.
 */
public final class LookalikeFromMap {

  private LookalikeFromMap() {}

  public static Lookalike fromMap(final Map<String, Object> map) {
    return new Lookalike(String.valueOf(map.get("city")));
  }
}
