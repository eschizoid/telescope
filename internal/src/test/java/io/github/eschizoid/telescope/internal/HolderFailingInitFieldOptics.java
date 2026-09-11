package io.github.eschizoid.telescope.internal;

import java.util.Map;
import java.util.function.Function;

/**
 * DELIBERATELY UNINITIALIZABLE holder fixture. Pair to {@link HolderFailingInit}. Its class
 * initializer throws, so every access arrives as an {@link ExceptionInInitializerError} and then,
 * on any later attempt, a {@code NoClassDefFoundError} naming the erroneous class.
 *
 * <p>The holder exists to skip the reflective build, so it is an optimization rather than a
 * dependency: {@link MetadataHolderProbe#probeFor} must answer empty and let the caller take the
 * slower path, which is the same answer it gives when no holder is present at all.
 *
 * <p>Hand-written (NOT codegen-emitted) so the shape is stable across processor regenerations.
 */
public final class HolderFailingInitFieldOptics {

  static {
    if (Boolean.parseBoolean("true")) {
      throw new IllegalStateException("this holder cannot initialize");
    }
  }

  private HolderFailingInitFieldOptics() {}

  public static Map<String, Object> constants() {
    return Map.of();
  }

  public static HolderFailingInit construct(final Function<String, Object> values) {
    return new HolderFailingInit((String) values.apply("name"));
  }
}
