package io.github.eschizoid.telescope.internal;

import java.util.Map;
import java.util.function.Function;

/**
 * DELIBERATELY MALFORMED holder fixture. Pair to {@link HolderWrongConstructReturn}. The {@code
 * construct(Function)} returns {@link Object} instead of {@link HolderWrongConstructReturn},
 * tripping the assignability check at line 167 of {@link MetadataHolderProbe}. Without this guard
 * the holder would later trigger a {@code ClassCastException} at first use; the plan-time
 * diagnostic surfaces it immediately at probe time with a clear "shape is wrong" message.
 */
public final class HolderWrongConstructReturnFieldOptics {

  private HolderWrongConstructReturnFieldOptics() {}

  public static Map<String, Object> constants() {
    return Map.of();
  }

  // Returns Object, NOT HolderWrongConstructReturn — should be rejected.
  public static Object construct(final Function<String, Object> values) {
    return new HolderWrongConstructReturn((String) values.apply("name"));
  }
}
