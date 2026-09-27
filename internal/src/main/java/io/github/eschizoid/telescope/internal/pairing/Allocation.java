package io.github.eschizoid.telescope.internal.pairing;

/**
 * What a declared container type is rebuilt as.
 *
 * <p>Written so one decision can serve both renderings: the runtime lift binds a constructor, and
 * the generated bridge writes a {@code new} expression. Only the lift reads it today, so the two
 * still answer separately until the processor is moved across.
 *
 * <p>The implementation is named rather than handed over as a type, because the two sides want
 * different things from it and a name is what they have in common. How its constructor is then
 * called is left to each renderer: a list's {@code int} is an element count, a hash container's is
 * a table capacity, and one of them has no sizing factory at all, so the call cannot be described
 * without describing the constructor.
 */
public sealed interface Allocation {
  /** The class to build. */
  record Build(String implName) implements Allocation {}

  /**
   * No allocation exists for this declared type, with the sentence to report. Carrying the reason
   * here is what will let both renderings refuse in the same words; the processor has its own
   * wording until it reads this.
   */
  record Refuse(String reason) implements Allocation {}
}
