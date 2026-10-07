package io.github.eschizoid.telescope.internal.pairing;

/**
 * How a rebuilt container is told the order its source kept.
 *
 * <p>The order is a property of the source value rather than of its declared type: a field written
 * as a plain {@code Set} can hold one ordered by a comparator. So none of these says whether there
 * is an order to carry. Each says what the rebuild does with one when the value turns out to have
 * it, and a source in natural order is built in natural order under all three.
 *
 * @param <T> the type handle of the world asking: {@code java.lang.reflect.Type} at run time,
 *     {@code TypeMirror} at compile time
 */
public sealed interface Ordering<T> {
  /** The rebuilt container keeps no order, so there is nothing to carry. */
  record None<T>() implements Ordering<T> {}

  /**
   * The source's comparator is handed to the rebuilt container's comparator constructor, whose
   * parameter is {@code parameter}, resolved against the arguments the field gave the class. A
   * renderer that has to name the comparator's type reads it from here.
   */
  record Carry<T>(T parameter) implements Ordering<T> {}

  /**
   * A source ordered by a comparator cannot be rebuilt without losing that order, and is refused
   * with {@code reason}. The reason travels with the decision so both renderers refuse in the same
   * words.
   */
  record Refuse<T>(String reason) implements Ordering<T> {}
}
