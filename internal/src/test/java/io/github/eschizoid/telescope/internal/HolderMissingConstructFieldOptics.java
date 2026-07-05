package io.github.eschizoid.telescope.internal;

import java.util.Map;

/**
 * DELIBERATELY MALFORMED holder fixture. Pair to {@link HolderMissingConstruct}. Has a valid {@code
 * constants()} but is MISSING the required {@code construct(Function)} method. Used to pin the
 * "missing construct" branch of {@link MetadataHolderProbe} — codegen out-of-sync (a runtime holder
 * probe against a holder with no construct emission yet) would land here.
 */
public final class HolderMissingConstructFieldOptics {

  private HolderMissingConstructFieldOptics() {}

  public static Map<String, Object> constants() {
    return Map.of();
  }
}
