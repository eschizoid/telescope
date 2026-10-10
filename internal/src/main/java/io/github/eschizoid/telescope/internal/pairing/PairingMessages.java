package io.github.eschizoid.telescope.internal.pairing;

import java.util.List;

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

  /**
   * A sorted set whose elements are converted, rebuilt from a source ordered by a comparator, which
   * orders the type being converted away from.
   */
  public static String comparatorAcrossConversion() {
    return (
      "Deep map: a custom sorted-set comparator cannot be reused with changed element types." +
      " Supply an explicit Mapping.via(...) row with a target comparator."
    );
  }

  /**
   * A sorted container rebuilt from a source ordered by a comparator, where the class built has no
   * public constructor that can be handed that comparator.
   */
  public static String noComparatorConstructor(final String implName) {
    return (
      "Deep map: " +
      implName +
      " declares no public constructor taking a Comparator, so the source's ordering cannot be carried" +
      " into it. Declare one, declare the field as the interface, or convert it with an explicit" +
      " Mapping.to(src, tgt, fwd, bwd) row."
    );
  }

  /**
   * A sorted map target whose key class does not implement {@code Comparable} and whose class
   * cannot be handed the comparator a source might carry.
   */
  public static String unorderableSortedKey(
    final String componentName,
    final String containerType,
    final String keyType
  ) {
    return (
      "Deep map: component '" +
      componentName +
      "' is a " +
      containerType +
      ", a sorted map whose key " +
      keyType +
      " does not implement Comparable, and which cannot be handed the comparator a source carries," +
      " so nothing can order its keys. Make " +
      keyType +
      " Comparable, declare the field as a map that keeps no order, declare a constructor taking a" +
      " Comparator, or supply an explicit Mapping.via(...) row that builds the map with a comparator."
    );
  }

  /**
   * An interface or abstract container type whose family builds a class that is not one of it, so
   * nothing a rebuild can make fits the field.
   */
  public static String noDefaultImplementation(final String declared, final String familyDefault) {
    return (
      "Deep map: " +
      declared +
      " has no instance of its own, and the class a rebuild builds in its place, " +
      familyDefault +
      ", is not a " +
      declared +
      ". Declare the field as a concrete container, or convert it with an explicit Mapping.via(...) row."
    );
  }

  /**
   * A container class with no no-argument constructor that the code rebuilding it can call: none at
   * all, a private one, or a package-private or protected one declared in a package the rebuild is
   * not generated into.
   */
  public static String noReachableConstructor(final String declared) {
    return (
      "Deep map: " +
      declared +
      " has no no-argument constructor a rebuild can call. A public one is called from anywhere, and a" +
      " package-private or protected one only by a rebuild generated into its own package. Declare one," +
      " or convert the field with an explicit Mapping.via(...) row."
    );
  }

  /** A container built from the class of its keys, declared without naming one. */
  public static String noKeyClass(final String declared) {
    return (
      "Deep map: " +
      declared +
      " is built from the class of its keys, and this declaration names none. Declare its key type, or" +
      " convert the field with an explicit Mapping.via(...) row."
    );
  }

  /**
   * The start of the refusal a sorted container's insert earns, up to the class of the element or
   * key it could not order, which only the value being inserted can name. A renderer that has the
   * value appends its class, {@link #unorderableInsertComparable}, and {@link
   * #unorderableInsertAdvice}; one writing code for a value it does not have writes the same three
   * pieces around the expression that will.
   */
  public static String unorderableInsertHead(final String container, final boolean map) {
    return "Deep map: " + container + (map ? " keeps its keys in order, and " : " keeps its elements in order, and ");
  }

  /** The middle of that refusal, after the class of the value it could not order. */
  public static String unorderableInsertComparable(final boolean comparable) {
    return comparable
      ? " could not be ordered there, though its type implements Comparable"
      : " could not be ordered there, and its type does not implement Comparable";
  }

  /**
   * The middle of that refusal where no value can be named: a bulk insert failed, and inserting the
   * same source one value at a time met nothing that failed.
   */
  public static String unorderableInsertUnnamed(final boolean map) {
    return (
      (map ? "a key" : "an element") +
      " could not be ordered there, which a second pass over the source did not meet again to name"
    );
  }

  /** The end of that refusal: where an ordering could come from instead. */
  public static String unorderableInsertAdvice(final boolean map) {
    return (
      (map ? ". Supply an ordering these keys accept" : ". Supply an ordering these elements accept") +
      " through a Mapping.via(...) row, or declare the target as a " +
      (map ? "map" : "set") +
      " that keeps no order. The cause is the cast itself."
    );
  }

  /**
   * Two enums whose constants do not line up by name in a direction the mapper converts. {@code
   * missingOnTarget} lists the source constants forward has nowhere to send, and {@code
   * missingOnSource} the target constants backward has nowhere to send; the caller passes an empty
   * list for a direction the mapper does not run.
   */
  public static String unmatchedEnumConstants(
    final String componentName,
    final String srcType,
    final String tgtType,
    final List<String> missingOnTarget,
    final List<String> missingOnSource
  ) {
    final var sb = new StringBuilder("Component '")
      .append(componentName)
      .append("' maps enum ")
      .append(srcType)
      .append(" to enum ")
      .append(tgtType)
      .append(" by constant name, but");
    if (!missingOnTarget.isEmpty()) {
      sb.append(" ").append(tgtType).append(" has no constant named ").append(String.join(", ", missingOnTarget));
      if (!missingOnSource.isEmpty()) sb.append(", and");
    }
    if (!missingOnSource.isEmpty()) {
      sb
        .append(" ")
        .append(srcType)
        .append(" has no constant named ")
        .append(String.join(", ", missingOnSource))
        .append(", which the backward direction needs");
    }
    sb.append(". Add the missing constants, or convert the component explicitly");
    if (missingOnTarget.isEmpty()) {
      sb.append(", or map forward only (Telescope.mapperForward, or @Bridge(lenient = true))");
    }
    return sb.append(".").toString();
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
