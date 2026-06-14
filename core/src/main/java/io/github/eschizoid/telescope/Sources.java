package io.github.eschizoid.telescope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Class-keyed bag of source objects consumed by {@link Telescope#merge(Class,
 * io.github.eschizoid.telescope.mapping.MergeStep[])} — the N-source forward-only mapper. Each
 * entry's runtime class is the lookup key; the merge engine reads the value for a row's source
 * class at forward time.
 *
 * <p>One concept, any arity. There are no {@code Sources2}/{@code Sources3} specializations because
 * the typed tuple variants didn't pay their way at higher arities and split the public surface
 * unnecessarily.
 *
 * <h2>Building</h2>
 *
 * <pre>{@code
 * // Varargs form — covers every common case in one line:
 * Sources two   = Sources.of(customer, audit);
 * Sources three = Sources.of(customer, audit, lineItem);
 * Sources six   = Sources.of(customer, audit, lineItem, tax, promo, channel);
 *
 * // Builder form — useful when sources are added conditionally or piece-by-piece:
 * Sources bag = Sources.builder().with(customer).with(audit).with(lineItem).build();
 * }</pre>
 *
 * <h2>Constraint — distinct runtime classes</h2>
 *
 * <p>Every source must have a distinct runtime class; passing two values of the same runtime class
 * to {@link #of(Object[])} or {@link Builder#with(Object)} throws {@link IllegalArgumentException}
 * at construction time with a precise diagnostic. This constraint mirrors how the merge engine
 * resolves rows: each {@code MergeStep.from(...)} row identifies its source by the accessor's
 * declaring class. If two same-typed sources are genuinely needed, pre-aggregate them into a named
 * holder record before the merge — records are final, so subclassing the offending type is not an
 * option.
 *
 * <p>Forward direction reads each row's source via {@link #byClass(Class)} at runtime. Backward is
 * unsupported on every multi-source mapper — the throw on the produced {@link
 * io.github.eschizoid.telescope.conversion.Mapper#backward} names the factory in its message.
 */
public final class Sources {

  private final Map<Class<?>, Object> bySrcClass;
  private final List<Object> ordered;

  private Sources(final Map<Class<?>, Object> bySrcClass, final List<Object> ordered) {
    this.bySrcClass = bySrcClass;
    this.ordered = ordered;
  }

  /**
   * Build a bag from the supplied source objects. Each entry's runtime class is used as the lookup
   * key; duplicate classes across the varargs throw {@link IllegalArgumentException}.
   */
  public static Sources of(final Object... sources) {
    Objects.requireNonNull(sources, "Sources.of: sources array is null");
    final var map = new LinkedHashMap<Class<?>, Object>();
    final var list = new ArrayList<>(sources.length);
    for (final var s : sources) {
      if (s == null) throw new IllegalArgumentException(
        "Sources.of: source at position " + list.size() + " is null. Every source in a merge bag must be non-null."
      );
      if (map.put(s.getClass(), s) != null) throw new IllegalArgumentException(
        "Sources.of: two sources share runtime class " +
          s.getClass().getName() +
          ". Each merge source must have a distinct class — for two same-typed sources, pre-aggregate them into a named holder record (records are final; subclassing is not available)."
      );
      list.add(s);
    }
    return new Sources(Map.copyOf(map), List.copyOf(list));
  }

  /** Open a {@link Builder} for fluent / conditional source assembly. */
  public static Builder builder() {
    return new Builder();
  }

  /** Number of sources in the bag. */
  public int arity() {
    return ordered.size();
  }

  /**
   * The runtime classes of all sources in the bag, in insertion order. Used by the merge engine's
   * forward-time diagnostic to name what IS present when a row's source class is missing — gives
   * the user actionable information without exposing the source values themselves.
   */
  public Set<Class<?>> classes() {
    return bySrcClass.keySet();
  }

  /**
   * Read slot {@code index} in insertion order (0-based). The merge engine uses {@link
   * #byClass(Class)} as its primary lookup; this method exists for diagnostic/debugging code paths
   * and for tests that want to assert ordering.
   */
  public Object slot(final int index) {
    return ordered.get(index);
  }

  /**
   * Read the source whose runtime class is {@code sourceClass}, or {@code null} if no such source
   * is in the bag. Used by the engine to dispatch each row to its source.
   */
  public Object byClass(final Class<?> sourceClass) {
    return bySrcClass.get(sourceClass);
  }

  /** Fluent builder. */
  public static final class Builder {

    private final Map<Class<?>, Object> entries = new LinkedHashMap<>();

    private Builder() {}

    /** Add one source. Same constraints as {@link Sources#of(Object[])}. */
    public Builder with(final Object source) {
      Objects.requireNonNull(source, "Sources.Builder.with: source is null");
      if (entries.put(source.getClass(), source) != null) throw new IllegalArgumentException(
        "Sources.Builder.with: two sources share runtime class " +
          source.getClass().getName() +
          ". Each merge source must have a distinct class — for two same-typed sources, pre-aggregate them into a named holder record (records are final; subclassing is not available)."
      );
      return this;
    }

    /** Build the immutable bag. */
    public Sources build() {
      return new Sources(Map.copyOf(entries), List.copyOf(entries.values()));
    }
  }
}
