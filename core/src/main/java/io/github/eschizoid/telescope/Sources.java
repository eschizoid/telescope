package io.github.eschizoid.telescope;

/**
 * Sealed parent of the multi-source tuple family consumed by {@link Telescope#merge(Class, Class,
 * Class, io.github.eschizoid.telescope.mapping.MergeStep2[])} and its arity-3 sibling. Each permit
 * holds N typed slots representing the N source objects whose fields flow into a single target on
 * the forward direction.
 *
 * <p>Slot indexing is 0-based. The internal engine reads slots via {@link #slot(int)} for arity-
 * generic dispatch; user code uses the typed record accessors ({@code first()}, {@code second()},
 * {@code third()}, ...).
 *
 * <h2>Family arity</h2>
 *
 * <p>Two arities ship today:
 *
 * <ul>
 *   <li>{@link Sources2} — covers the ~60% of real-world enterprise multi-source mappers (entity +
 *       audit, request + context, etc.) flagged in {@code PLAN.md} item 1.3.
 *   <li>{@link Sources3} — covers the next ~30% (entity + audit + line-item, command + actor +
 *       env, etc.).
 * </ul>
 *
 * <p>The sealed permits clause is deliberately open-ended in spirit: adding {@code Sources4} or
 * {@code Sources5} is mechanical. Copy {@link Sources3}, add one more typed slot, register a
 * sibling {@code MergeStep4} / {@code MergeStep5}, and add the matching {@code Telescope.merge}
 * overload. The engine in {@code Merge.java} is already slot-indexed (not slot-named), so the
 * dispatch loop carries through unchanged.
 *
 * <p>Arity &gt; 5 is the explicit smell threshold: at that point the domain model is asking for
 * pre-aggregation into a holder type rather than a wider tuple. Telescope makes that judgment by
 * not shipping {@code Sources6+} — users who genuinely need it must pre-aggregate, and the
 * pre-aggregation usually clarifies the design.
 *
 * <h2>Building tuples</h2>
 *
 * <p>The {@code Sources.of(...)} static factories collapse the {@code new Sources2<>(a, b)}
 * ceremony at call sites:
 *
 * <pre>{@code
 * Mapper<Sources2<Customer, Audit>, Profile> mapper = ...;
 * Profile p = mapper.forward(Sources.of(customer, audit));
 * }</pre>
 */
public sealed interface Sources permits Sources2, Sources3 {
  /** Arity = number of source slots. */
  int arity();

  /**
   * Read slot {@code index} (0-based). Used by the internal engine for arity-generic dispatch;
   * user code should prefer the typed accessor methods on each permit.
   *
   * @throws IndexOutOfBoundsException if {@code index} is out of range for this arity
   */
  Object slot(int index);

  /** Build a 2-source tuple. Shortcut for {@code new Sources2<>(a, b)}. */
  static <A, B> Sources2<A, B> of(final A a, final B b) {
    return new Sources2<>(a, b);
  }

  /** Build a 3-source tuple. Shortcut for {@code new Sources3<>(a, b, c)}. */
  static <A, B, C> Sources3<A, B, C> of(final A a, final B b, final C c) {
    return new Sources3<>(a, b, c);
  }
}
