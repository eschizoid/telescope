package io.github.eschizoid.telescope.internal;

import java.util.Map;
import java.util.function.Function;

/**
 * DELIBERATELY MALFORMED holder fixture. Pair to {@link HolderNonStaticConstants}. The {@code
 * constants()} method exists but is NON-STATIC. {@link MetadataHolderProbe} must reject this with
 * the "shape is wrong" diagnostic, NOT silently accept a non-static method.
 */
public final class HolderNonStaticConstantsFieldOptics {

  private HolderNonStaticConstantsFieldOptics() {}

  // Non-static — the shape check at line 123 of MetadataHolderProbe must fire here.
  public Map<String, Object> constants() {
    return Map.of();
  }

  public static HolderNonStaticConstants construct(final Function<String, Object> values) {
    return new HolderNonStaticConstants((String) values.apply("name"));
  }
}
