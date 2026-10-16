package io.github.eschizoid.telescope.internal.pairing;

/**
 * How a bean property's read type, its getter's return type, reaches its write type, the parameter
 * type of its setter. {@link PairingRules#setterFit} decides it once for every path.
 *
 * <p>A path that converts a value only to write it, such as fromMap, converts to the write type
 * where a value of the read type cannot be passed to the setter, and to the read type otherwise. A
 * path that writes back a value it read, or a value it converted for the read type, needs the read
 * type to reach the write type: generated code where javac passes it without a cast, which is
 * {@link #SAME} and {@link #CONVERTS}, and the reflective writers wherever some value of the read
 * type is an instance of the write type, which adds {@link #NARROWS}, checked for each value at the
 * call as a cast would be. Neither writes through a {@link #DISJOINT} setter.
 */
public enum SetterFit {
  /** The same type, or a primitive and its wrapper. */
  SAME,
  /**
   * Every value of the read type can be passed to the setter: a primitive widening, a reference
   * widening, or either one after boxing or unboxing, as Java's method invocation allows.
   */
  CONVERTS,
  /**
   * Some values of the read type can be passed to the setter and some cannot: the setter takes a
   * subtype of the read type, or a type related to it only through an interface.
   */
  NARROWS,
  /** No value of the read type can be passed to the setter. */
  DISJOINT;

  /** Whether generated code can pass a value of the read type to the setter. */
  public boolean compiles() {
    return this == SAME || this == CONVERTS;
  }

  /** Whether a reflective writer can pass some value of the read type to the setter. */
  public boolean reflects() {
    return this != DISJOINT;
  }
}
