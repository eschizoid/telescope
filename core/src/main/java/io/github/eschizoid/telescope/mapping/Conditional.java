package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Predicate-gated wrapper around a telescope-based {@link Mapping} row — closes MapStruct's
 * {@code @Condition} for whole-source predicate gating. When the predicate accepts the source
 * object, the inner row applies normally; when it rejects, the inner row is skipped at forward
 * time.
 *
 * <pre>{@code
 * Telescope.mapper(Order.class, OrderDto.class,
 *     to(Order::id, OrderDto::id),
 *     when(o -> o.shipping() != null,
 *         to(Telescope.of(Order.class).field(Order::shipping).field(Shipping::country),
 *            OrderDto::shipCountry)));
 * }</pre>
 *
 * <p><b>Scope.</b> Only telescope-based rows participate — {@link TelescopeTo}, {@link
 * FromTelescopeTo}, {@link TelescopeToTelescope}, {@link Constant}, {@link Compute}. Field-iso rows
 * (the plain {@code to(srcAcc, tgtAcc)} family, {@code via}, {@code drop}) reject at construction
 * with a precise IAE pointing at the alternative: {@code Mapping.toOrElse(src, tgt, default,
 * predicate)} already expresses "if predicate matches source value, use default" at the field
 * level. {@code when(...)} is the complementary tool — whole-source predicate gating on deep writes
 * and stamping rows.
 *
 * <p><b>Forward semantics.</b> If {@code predicate.test(source)} returns {@code true}, the inner
 * row's forward effect applies as usual. Otherwise the row is skipped entirely; the target field
 * retains whatever the base structural Iso produced (typically {@code null} or the type default,
 * depending on the row kind — e.g. a skipped {@link TelescopeTo} leaves the target leaf at the
 * recursive-default allocation; a skipped {@link Constant} leaves it at the auto-mapped value).
 *
 * <p><b>Backward semantics — forward-only.</b> Same retraction semantics as {@link Constant} /
 * {@link Compute}: backward direction skips the row entirely. The source rebuild leaves the
 * corresponding slot at the {@code baseS} value (whatever the base structural Iso reconstructed).
 * Forward(backward(t)) is intentionally asymmetric for predicate-gated rows — the predicate is not
 * evaluable from a target, and skipping backward keeps the semantics simple and consistent with
 * MapStruct's {@code @Condition}, which is also a forward-only construct.
 *
 * <p><b>Nesting.</b> {@code when(when(...))} is rejected at construction. For multi-predicate
 * logic, compose at the predicate layer ({@code Predicate#and} / {@code Predicate#or}).
 *
 * <p><b>Predicate purity AND thread-safety.</b> The predicate is invoked exactly once per top-level
 * forward call for each {@code when(...)} row at the outer pair. Recursive structural mapping does
 * NOT re-invoke the predicate at nested pairs — Conditional rows pin to the top-level pair (see the
 * "Pinning" paragraph below). The engine makes no broader guarantee under future cycle-detection or
 * sub-pair caching changes, however, so predicates should be pure / side-effect-free. Predicates
 * that need to fire on every forward call should land their effect in a {@code Mapper.afterForward}
 * hook instead, where the contract is explicit.
 *
 * <p>The same {@code Conditional} instance is reused across every {@code mapper.forward(...)} call
 * (the cached Mapper holds it indefinitely). Production deployments under Spring / Quarkus pin
 * {@code TelescopeMapperRegistry} as a singleton, so the predicate runs concurrently across request
 * threads. Closures that mutate external state (counters, log buffers, ThreadLocal stack unwinding)
 * must be thread-safe.
 *
 * <p><b>Same target field — last write wins.</b> Multiple Conditional rows whose inner telescopes
 * claim the same target leaf compose in insertion order — when both predicates accept, the later
 * row overwrites the earlier. The engine's strict duplicate-target check exempts telescope-based
 * rows (Conditional and the inner rows it wraps) because telescope writes may descend to different
 * deep leaves; that exemption applies here too. Order your {@code when(...)} rows so the
 * most-specific case lands last, or refactor to a single row whose predicate disambiguates ({@code
 * when(p1.and(p2.negate()), rowA)}, {@code when(p1.and(p2), rowB)}).
 *
 * <p><b>Pinning — top-level only.</b> Every supported inner row carries at least one {@link
 * Telescope}, whose root class isn't recoverable at runtime (generics erased). Inner rows therefore
 * report {@code null} for {@code sourceClass} / {@code targetClass}; {@code Conditional}
 * unconditionally does the same (overriding to {@code null} keeps the contract explicit and
 * prevents a future inner permit with a non-null class from silently breaking the top-level pinning
 * the engine relies on — {@link io.github.eschizoid.telescope.DeepMap} pins {@code null}-classed
 * rows to the outer {@code (topSource, topTarget)} pair). A {@code when(...)} row therefore
 * evaluates its predicate against the <em>top-level source</em>, not against any nested type the
 * inner row's telescope navigates into. For predicate gating at a nested type pair (e.g. evaluating
 * against {@code Customer} when the outer mapper is {@code Order ↔ OrderDto}), use a top-level
 * predicate that traverses to the nested object: {@code when(order -> order.customer() != null &&
 * order .customer().email() != null, inner)}.
 *
 * <p>Constructed via {@link Mapping#when(Predicate, Mapping)} — record is package-private surface.
 *
 * @param predicate evaluated against the source object on every forward call
 * @param inner the telescope-based row this conditional gates; must not be another {@code
 *     Conditional}
 * @param <A> source type
 * @param <B> target type
 */
public record Conditional<A, B>(Predicate<? super A> predicate, Mapping<A, B> inner) implements Mapping<A, B> {
  public Conditional {
    Objects.requireNonNull(predicate, "predicate");
    Objects.requireNonNull(inner, "inner");
    if (inner instanceof Conditional<?, ?>) {
      throw new IllegalArgumentException(
        "Mapping.when(...) does not nest. Combine predicates with Predicate#and / Predicate#or " +
          "on a single when(...) row instead of stacking when(when(...))."
      );
    }
    if (!isSupportedInner(inner)) {
      final var fieldSuffix = describeFieldClaim(inner);
      final var alternativeHint =
        inner instanceof ForwardOnlyTransformTo<?, ?, ?, ?>
          ? "Mapping.toOneWay(src, tgt, fn) rows cannot be predicate-gated externally — fold the " +
            "predicate into the forward function (return the source value or a default based on the " +
            "predicate)."
          : "For field-level predicate gating on flat accessors, use Mapping.toOrElse(src, tgt, " +
            "defaultValue, predicate) — the source-value predicate applies the default when matched.";
      throw new IllegalArgumentException(
        "Mapping.when(...) wraps only telescope-based rows: to(srcAcc, targetTelescope), " +
          "to(srcTelescope, tgtAcc), to(srcTelescope, tgtTelescope), zip(srcTelescope, tgtTelescope), " +
          "constant(targetTelescope, value), compute(targetTelescope, supplier). " +
          alternativeHint +
          " Got inner row of kind " +
          inner.getClass().getSimpleName() +
          fieldSuffix +
          "."
      );
    }
  }

  private static boolean isSupportedInner(final Mapping<?, ?> m) {
    return (
      m instanceof TelescopeTo<?, ?, ?> ||
      m instanceof FromTelescopeTo<?, ?, ?> ||
      m instanceof TelescopeToTelescope<?, ?, ?> ||
      m instanceof Constant<?, ?, ?> ||
      m instanceof Compute<?, ?, ?>
    );
  }

  /**
   * Surface the inner row's source/target field names (if known) so the rejection message points at
   * the actual call site the user wrote. Telescope-based rows return {@code null} for these
   * accessors (root class erased), so we fall back to the bare kind name in that case.
   */
  private static String describeFieldClaim(final Mapping<?, ?> m) {
    final var src = m.sourceField();
    final var tgt = m.targetField();
    if (src == null && tgt == null) return "";
    return " (" + (src == null ? "<telescope>" : src) + " → " + (tgt == null ? "<telescope>" : tgt) + ")";
  }

  /**
   * Always {@code null}: telescope-based inner rows have an erased root class on at least one side,
   * so they pin to the outer {@code (topSource, topTarget)} pair. Returning {@code null}
   * unconditionally keeps this contract explicit — see class-level javadoc, "Pinning — top-level
   * only".
   */
  @Override
  public Class<A> sourceClass() {
    return null;
  }

  /** Always {@code null} — same rationale as {@link #sourceClass()}. */
  @Override
  public Class<B> targetClass() {
    return null;
  }

  /**
   * Always {@code null} — the inner row's source-field name (if any) is consulted directly by the
   * routing layer via {@link #inner()}; the wrapper itself contributes no field claim.
   */
  @Override
  public String sourceField() {
    return null;
  }

  /** Always {@code null} — same rationale as {@link #sourceField()}. */
  @Override
  public String targetField() {
    return null;
  }
}
