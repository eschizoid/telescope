package io.github.eschizoid.telescope.internal.pairing;

import java.util.HashSet;
import java.util.List;

/**
 * How the constants of two enums line up by name: the source constants the target has no constant
 * of the same name for, and the target constants the source has none for, each in its enum's
 * declaration order.
 *
 * <p>Forward converts each source constant to the target constant of the same name, so it needs
 * {@link #missingOnTarget} empty. Backward converts the other way and needs {@link
 * #missingOnSource} empty. A mapper that converts in both directions needs both.
 *
 * @param missingOnTarget source constants with no same-named target constant
 * @param missingOnSource target constants with no same-named source constant
 */
public record EnumCorrespondence(List<String> missingOnTarget, List<String> missingOnSource) {
  public EnumCorrespondence {
    missingOnTarget = List.copyOf(missingOnTarget);
    missingOnSource = List.copyOf(missingOnSource);
  }

  /** The correspondence between two enums, given their constant names in declaration order. */
  public static EnumCorrespondence of(final List<String> sourceConstants, final List<String> targetConstants) {
    final var source = new HashSet<>(sourceConstants);
    final var target = new HashSet<>(targetConstants);
    return new EnumCorrespondence(
      sourceConstants
        .stream()
        .filter(name -> !target.contains(name))
        .toList(),
      targetConstants
        .stream()
        .filter(name -> !source.contains(name))
        .toList()
    );
  }

  /**
   * Whether every constant converts in each direction the mapper runs: forward alone when {@code
   * forwardOnly}, and both forward and backward otherwise.
   */
  public boolean converts(final boolean forwardOnly) {
    return missingOnTarget.isEmpty() && (forwardOnly || missingOnSource.isEmpty());
  }
}
