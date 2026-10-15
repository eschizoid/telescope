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

  /**
   * A constructor whose known parameter names are partly properties: binding by name would leave
   * the others without a value, and binding by position could hand one property's value to a
   * parameter named after another.
   */
  public static String constructorParametersNotProperties(final String binaryName, final List<String> unmatched) {
    return (
      "Cannot write " +
      binaryName +
      " through its constructor: the parameters " +
      unmatched +
      " are named after no property, while the others are. Telescope matches a constructor's" +
      " arguments by parameter name when every name is a property, and by position when none is." +
      " Name every parameter after a property, or write the class through setters or a builder."
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

  /**
   * One row in a {@link #rowsNeverVisited} refusal, by the raw method names of its accessors. A
   * side given as a telescope path has no accessor and is written {@code <path>}.
   */
  public static String unvisitedRow(final String sourceMethod, final String targetMethod) {
    return (sourceMethod == null ? "<path>" : sourceMethod) + " -> " + (targetMethod == null ? "<path>" : targetMethod);
  }

  /**
   * The rows keyed to one type pair the mapper never visits, as one entry of a {@link
   * #rowsNeverVisited} refusal. Classes are named by binary name; {@code rows} come from {@link
   * #unvisitedRow}.
   */
  public static String unvisitedPair(final String source, final String target, final List<String> rows) {
    return "(" + source + " -> " + target + "): [" + String.join(", ", rows) + "]";
  }

  /**
   * Rows keyed to type pairs the mapper's recursion never reaches, which would otherwise be ignored
   * without a trace. Classes are named by binary name; {@code pairs} come from {@link
   * #unvisitedPair}, in the order the first row of each was given.
   */
  public static String rowsNeverVisited(final String topSource, final String topTarget, final List<String> pairs) {
    return (
      "Mapping rows bound to a type pair the mapper (" +
      topSource +
      " -> " +
      topTarget +
      ") never visits — they would be silently ignored: " +
      String.join("; ", pairs) +
      ". Bind the rows to a type pair this mapper's recursion actually reaches, or remove them."
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

  /**
   * A row that writes the mapper's target ({@code constant}, {@code compute}, {@code to} or {@code
   * zip}) whose target names a property of a class the mapper's target neither is nor extends. Such
   * a row always writes on the mapper's target, so the property it names is not one the target has.
   */
  public static String targetRowOffTarget(
    final String source,
    final String target,
    final String row,
    final String owner,
    final String tgtField
  ) {
    return (
      "Deep map " +
      source +
      " → " +
      target +
      ": " +
      row +
      " row writes '" +
      tgtField +
      "' of " +
      owner +
      ", which " +
      target +
      " neither is nor extends. Name the property through " +
      target +
      " or a class it extends."
    );
  }

  /**
   * A row whose target path narrows the mapper's target, with {@code as(...)}, to a class the
   * target neither is nor extends. The narrowing never matches the target the mapper builds, so the
   * row would never write.
   */
  public static String targetPathNarrowsOffTarget(
    final String source,
    final String target,
    final String row,
    final String narrowed
  ) {
    return (
      "Deep map " +
      source +
      " → " +
      target +
      ": " +
      row +
      " row's target path narrows to " +
      narrowed +
      ", which " +
      target +
      " neither is nor extends, so it never writes on " +
      target +
      ". Narrow to " +
      target +
      " or a class it extends."
    );
  }

  /**
   * A row whose target path starts at a class the mapper's target neither is, extends, nor is
   * extended by, and that cannot be started again at the target because its first step off the root
   * is not an accessor navigation: the path starts at a lens, reaches its first property through an
   * iso or a bridge, or writes the root value itself. Such a path can never write the target.
   */
  public static String targetPathNotOnTarget(
    final String source,
    final String target,
    final String row,
    final String root
  ) {
    return (
      "Deep map " +
      source +
      " → " +
      target +
      ": " +
      row +
      " row's target path starts at " +
      root +
      ", which " +
      target +
      " neither is nor extends nor is extended by, and cannot be started again at " +
      target +
      " because its first step off the root is not an accessor navigation such as field(...)." +
      " Start the path at " +
      target +
      "."
    );
  }

  /**
   * A {@code constant} row that replaces the mapper's target with its value, through a path started
   * at a supertype or subtype of the target, whose value is not an instance of the target.
   */
  public static String targetValueOffTarget(
    final String source,
    final String target,
    final String row,
    final String valueClass
  ) {
    return (
      "Deep map " +
      source +
      " → " +
      target +
      ": " +
      row +
      " row replaces the whole " +
      target +
      " with a value of class " +
      valueClass +
      ", which is not a " +
      target +
      ". Use a value of class " +
      target +
      "."
    );
  }

  /**
   * A write through a row's target path, started at a supertype or subtype of the mapper's target
   * or at a class that is not known, that returned something other than a target instance or null:
   * a lens setter, iso or bridge that built another class in place of the target it was given.
   */
  public static String targetPathWroteOffTarget(
    final String source,
    final String target,
    final String row,
    final String writtenClass
  ) {
    return (
      "Deep map " +
      source +
      " → " +
      target +
      ": " +
      row +
      " row's target path returned " +
      writtenClass +
      " from a write on " +
      target +
      ", which is not a " +
      target +
      ". A lens or iso started at another class must return the instance it is given, or another " +
      target +
      "."
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

  /**
   * A bean property whose setter cannot take a value of the type its getter returns, on a path that
   * writes such a value: a mapper filling the property from a source, a lens, a navigator, or a
   * patch.
   */
  public static String setterCannotTake(
    final String owner,
    final String property,
    final String setter,
    final String readType,
    final String writeType
  ) {
    return (
      "Property '" +
      property +
      "' of " +
      owner +
      " is read as " +
      readType +
      ", but its setter " +
      setter +
      " takes " +
      writeType +
      ", so a value of the property's type cannot be written through it. Declare the setter with" +
      " the getter's type, or write the property through a conversion that produces " +
      writeType +
      "."
    );
  }

  /** A bean property with several setters, none of which Java would bind its value to alone. */
  public static String ambiguousSetters(
    final String owner,
    final String property,
    final String readType,
    final List<String> setters
  ) {
    return (
      "Property '" +
      property +
      "' of " +
      owner +
      " has several setters, " +
      String.join(", ", setters) +
      ", and no single one takes its type " +
      readType +
      " more specifically than the rest. Remove the extra setters, or add one that takes " +
      readType +
      "."
    );
  }
}
