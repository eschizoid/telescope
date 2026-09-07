package io.github.eschizoid.telescope.internal;

import java.io.Serializable;
import java.util.function.Function;

/** Loaded in an isolated class loader by the cache-lifetime regression test. */
public final class UnloadableReference {

  private UnloadableReference() {}

  public static Serializable create() {
    return (Function<String, String> & Serializable) String::trim;
  }
}
