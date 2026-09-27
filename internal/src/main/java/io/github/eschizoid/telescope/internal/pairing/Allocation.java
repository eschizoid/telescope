package io.github.eschizoid.telescope.internal.pairing;

/**
 * What a declared container type is rebuilt as, decided once for both the runtime lift and the
 * generated bridge.
 *
 * <p>The implementation is named rather than handed over as a type, because the two sides want
 * different things from it: one binds a constructor, the other writes the name into source. A name
 * is what they have in common, and it keeps this decision free of either world's type handles.
 *
 * <p>Sizing is a property of the constructor being called, not of the call. A hash container's
 * {@code int} is a table capacity and a list's is an element count, so the two cannot be rendered
 * the same way even though both are "size it from the source" — which is why the decision names the
 * kind of argument and leaves each side to write it.
 */
public sealed interface Allocation {
  /** The class to build, and what its constructor should be told about the source. */
  record Build(String implName, Sizing sizing) implements Allocation {}

  /**
   * No allocation exists for this declared type. The reason is the diagnostic both sides report, so
   * an adopter meets the same sentence whichever path refused.
   */
  record Refuse(String reason) implements Allocation {}

  /** What the chosen constructor does with a size, or with the source's ordering. */
  enum Sizing {
    /** No argument. Either the constructor takes none, or its {@code int} means something else. */
    NONE,
    /** An exact count of elements, as {@code ArrayList} and {@code ArrayDeque} read it. */
    ELEMENT_COUNT,
    /** A hash table sized to hold that many without resizing, which is not the same number. */
    TABLE_CAPACITY,
    /** The source's comparator, for a container that keeps an order. */
    SOURCE_ORDERING,
  }
}
