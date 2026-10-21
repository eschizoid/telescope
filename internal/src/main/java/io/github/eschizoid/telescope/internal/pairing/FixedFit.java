package io.github.eschizoid.telescope.internal.pairing;

/**
 * What a value handed across unchanged owes the side it is written into, where one side fixes a
 * type variable the other passes through. {@link PairingRules#fixedFit} decides it once for every
 * path.
 *
 * <p>A pair read as declared is one type on both sides, so it converts nothing. Each side's own
 * class may still say more about the value than the declared type does: {@code class Names extends
 * Base<String, String>} holds a {@code String} where {@code Base<K, V>} declares a {@code V}, and a
 * {@code class Page<X> extends Base<String, X>} given an {@code Integer} hands an {@code Integer}
 * across to it.
 *
 * @param <T> the world's type handle
 */
public sealed interface FixedFit<T> {
  /** Every value the sending side holds fits the receiving side, so nothing is checked. */
  record Fits<T>() implements FixedFit<T> {}

  /**
   * A single value whose class is checked against {@code fixed}, the class the receiving side fixes
   * the variable to, as it is written. A value of another class is refused naming the property and
   * both classes.
   */
  record Check<T>(T fixed) implements FixedFit<T> {}

  /**
   * A value given type arguments, a container or any other generic class, that cannot be shown to
   * have those the receiving side admits. Nothing checks a value's arguments as it is written, so
   * the pair is refused with {@code message} when it is planned, for {@code reason}.
   */
  record Refused<T>(Reason reason, String message) implements FixedFit<T> {}

  /** Why a value handed across unchanged is refused. */
  enum Reason {
    /** Its type arguments are not shown to be ones the receiving side admits. */
    ARGUMENTS,
    /**
     * It is copied, and every argument fits a fresh copy, but the copy can keep the comparator the
     * source orders by, which takes only the sending side's element type, or a map's key type, and
     * that argument differs.
     */
    ORDERED_COPY,
  }
}
