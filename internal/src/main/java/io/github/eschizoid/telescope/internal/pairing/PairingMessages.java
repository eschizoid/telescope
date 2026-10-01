package io.github.eschizoid.telescope.internal.pairing;

/**
 * The single source of truth for every diagnostic the shared pairing decisions emit. The runtime
 * throws these strings at mapper construction; the compile-time verifier reports the same strings
 * as compiler errors — byte-identical, so a user who has seen one recognizes the other. Wording
 * changes happen here and nowhere else.
 */
public final class PairingMessages {

  private PairingMessages() {}

  /** A {@code writeBean} hint targeting a record — the hint can never apply. */
  public static String writeBeanTargetsRecord(final String binaryName) {
    return (
      "writeBean hint targets a record class (" +
      binaryName +
      "). Records are always reconstructed via the canonical constructor; the hint cannot apply. " +
      "Remove the writeBean(...) row, or move it to the bean side of the mapping."
    );
  }

  /** Two {@code writeBean} hints for the same target class. */
  public static String duplicateWriteBeanHint(final String binaryName) {
    return (
      "Duplicate writeBean hint for " +
      binaryName +
      ". Each target class may declare at most one writeBean(...) row per Telescope.map(...) call."
    );
  }

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

  /**
   * A generic container used raw, paired with a container that fixes element types other than
   * {@code Object}: nothing says the raw side's elements are of those types.
   */
  public static String unprovableRawElements(final String componentName, final String rawType, final String fixedType) {
    return (
      "Deep map: component '" +
      componentName +
      "' pairs " +
      rawType +
      ", a generic container used raw, with " +
      fixedType +
      ", which fixes its element types. Nothing says the raw side's elements are of those types, so they " +
      "cannot be copied across unconverted. Declare the raw side's type arguments, or convert the component " +
      "with an explicit Mapping.to(src, tgt, fwd, bwd) row."
    );
  }

  /** A sorted set target whose converted element class does not implement {@code Comparable}. */
  public static String unorderableSortedElement(
    final String componentName,
    final String containerType,
    final String elementType
  ) {
    return (
      "Deep map: component '" +
      componentName +
      "' is a " +
      containerType +
      ", a sorted set whose element " +
      elementType +
      " does not implement Comparable. The source's elements are converted to it, so no" +
      " comparator the source carries can order them either. Make " +
      elementType +
      " Comparable, declare the field as a set that keeps no order, or supply an explicit" +
      " Mapping.via(...) row that builds the set with a comparator."
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
