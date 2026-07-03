package io.github.eschizoid.telescope.internal.pairing;

/**
 * The single source of truth for every pairing diagnostic. The runtime throws these strings at
 * mapper construction; the compile-time verifier reports the same strings as compiler errors —
 * byte-identical, so a user who has seen one recognizes the other. Wording changes happen here and
 * nowhere else.
 */
public final class PairingMessages {

  private PairingMessages() {}

  /** Duplicate override row claiming an already-claimed source field. */
  public static String duplicateSourceRow(final String source, final String target, final String srcField) {
    return (
      "Deep map " +
      source +
      " → " +
      target +
      ": duplicate override row for source field '" +
      srcField +
      "'. Each (source, target) type pair may declare at most one row per source field."
    );
  }

  /** Duplicate override row claiming an already-claimed target field. */
  public static String duplicateTargetRow(final String source, final String target, final String tgtField) {
    return (
      "Deep map " +
      source +
      " → " +
      target +
      ": duplicate override row for target field '" +
      tgtField +
      "'. Each (source, target) type pair may declare at most one row per target field."
    );
  }

  /** Strict-bijection failure: a target field has no same-name source and no row. */
  public static String noSameNameSource(
    final String source,
    final String target,
    final String tgtSlot,
    final String srcSlot,
    final String name
  ) {
    return (
      "Deep map " +
      source +
      " → " +
      target +
      ": target " +
      tgtSlot +
      " '" +
      name +
      "' has no same-name source " +
      srcSlot +
      ". Add a rename row to(sourceAccessor, targetAccessor) that maps to '" +
      name +
      "'."
    );
  }

  /** Strict-bijection failure: a source field has no same-name target and no row. */
  public static String noSameNameTarget(
    final String source,
    final String target,
    final String srcSlot,
    final String tgtSlot,
    final String name
  ) {
    return (
      "Deep map " +
      source +
      " → " +
      target +
      ": source " +
      srcSlot +
      " '" +
      name +
      "' has no same-name target " +
      tgtSlot +
      ". Add a rename row to(sourceAccessor, targetAccessor) that consumes '" +
      name +
      "'."
    );
  }

  /**
   * Map-valued pair whose key types differ — auto-lifting preserves source keys, so keys must
   * match.
   */
  public static String incompatibleMapKeys(final String componentName, final String srcKey, final String tgtKey) {
    return (
      "Deep map: component '" +
      componentName +
      "' has incompatible Map key types — source " +
      srcKey +
      " vs target " +
      tgtKey +
      ". Key types must match exactly; auto-lifting preserves the source keys."
    );
  }

  /** Terminal shape mismatch — no branch of the compatibility lattice applies. */
  public static String incompatibleShapes(final String componentName, final String srcType, final String tgtType) {
    return (
      "Deep map: component '" +
      componentName +
      "' has incompatible source/target shapes — " +
      srcType +
      " vs " +
      tgtType +
      ". Shapes must match: same scalar, both records/beans, or both same-kind container. For " +
      "differing scalar types, add a to(src, tgt, forward, backward) row to supply the conversion."
    );
  }
}
