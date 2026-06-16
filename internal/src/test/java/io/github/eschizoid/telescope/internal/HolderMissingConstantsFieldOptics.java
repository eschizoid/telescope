package io.github.eschizoid.telescope.internal;

import java.util.function.Function;

/**
 * DELIBERATELY MALFORMED holder fixture. Pair to {@link HolderMissingConstants}. Has a {@code
 * construct(Function)} method but is MISSING the required {@code public static Map<String, Object>
 * constants()}. {@link MetadataHolderProbe#probeFor} must throw {@code IllegalStateException} with
 * the precise "missing the required" diagnostic when loading this holder.
 *
 * <p>Hand-written (NOT codegen-emitted) so the malformed shape is stable across processor
 * regenerations.
 */
public final class HolderMissingConstantsFieldOptics {

  private HolderMissingConstantsFieldOptics() {}

  public static HolderMissingConstants construct(final Function<String, Object> values) {
    return new HolderMissingConstants((String) values.apply("name"));
  }
}
