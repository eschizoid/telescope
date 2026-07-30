package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.internal.Reflective;
import io.github.eschizoid.telescope.internal.optics.Iso;
import io.github.eschizoid.telescope.mapping.Compute;
import io.github.eschizoid.telescope.mapping.Conditional;
import io.github.eschizoid.telescope.mapping.Constant;
import io.github.eschizoid.telescope.mapping.Drop;
import io.github.eschizoid.telescope.mapping.ForwardOnlyTransformTo;
import io.github.eschizoid.telescope.mapping.FromTelescopeTo;
import io.github.eschizoid.telescope.mapping.Mapping;
import io.github.eschizoid.telescope.mapping.SameTypedTo;
import io.github.eschizoid.telescope.mapping.TelescopeTo;
import io.github.eschizoid.telescope.mapping.TelescopeToTelescope;
import io.github.eschizoid.telescope.mapping.TypedTransformTo;
import io.github.eschizoid.telescope.mapping.Via;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;

/**
 * Telescope-fixup subsystem for {@link DeepMap}: composes the telescope-based {@link Mapping} rows
 * ({@link TelescopeTo}, {@link FromTelescopeTo}, {@link TelescopeToTelescope}, {@link Constant},
 * {@link Compute}, optionally gated by {@link Conditional}) as post-fixup overlays on top of the
 * base structural {@link Iso} the resolver assembled.
 *
 * <p>Two exhaustive per-row overlay switches drive the subsystem — one for the forward direction
 * ({@code applyForward}) and one for the backward direction ({@code applyBackward}) — so a new
 * permit of the sealed {@code Mapping} family must declare its effect in both directions or the
 * compiler rejects the switch. Structural rows never reach these dispatches (the resolver routes
 * them to its field-claim tail); their branches are fail-fast routing guards. {@link Conditional}
 * rows are peeled before dispatch: the predicate gates the inner row's forward effect (with a
 * decorated diagnostic when the predicate throws) and the backward direction is a documented no-op.
 * {@link TelescopeToTelescope.Kind#ZIP} rows enforce source/target focus cardinality before writing
 * positionally.
 */
final class TelescopeFixups {

  private TelescopeFixups() {}

  /**
   * Compose post-fixups on top of the base {@link Iso} produced by {@code DeepMap}'s structural
   * assembly. Four telescope-based row shapes route through this single wrapper; each contributes a
   * forward overlay and a backward overlay built from the lattice's public {@code Telescope.set} /
   * {@code Telescope.read} (and {@code toList} / {@code updateIndexed} for the {@link
   * TelescopeToTelescope.Kind#ZIP} case).
   *
   * <ul>
   *   <li>{@link TelescopeTo} (flat src → nested tgt): forward {@code tgtT.set(t,
   *       srcAcc.apply(s))}; backward rebuilds {@code s} with {@code sourceField} = {@code
   *       tgtT.read(t)}.
   *   <li>{@link FromTelescopeTo} (nested src → flat tgt): forward rebuilds {@code t} with {@code
   *       targetField} = {@code srcT.read(s)}; backward rebuilds {@code s} via {@code srcT.set(s,
   *       tgtAcc.apply(t))}.
   *   <li>{@link TelescopeToTelescope} with {@link TelescopeToTelescope.Kind#BROADCAST} (nested ↔
   *       nested, broadcast): forward {@code tgtT.set(t, srcT.read(s))}; backward {@code
   *       srcT.set(s, tgtT.read(t))}. When either side is many-focus the lattice's intrinsic
   *       broadcast / first-focus semantics apply — no extra machinery here.
   *   <li>{@link TelescopeToTelescope} with {@link TelescopeToTelescope.Kind#ZIP} (nested ↔ nested,
   *       positional N:N): forward reads {@code srcT.toList(s)} and writes positionally via {@code
   *       tgtT.updateIndexed(t, ...)} with cardinality enforcement; backward mirrors.
   * </ul>
   *
   * <p>All reads / writes go through the lattice's public {@link Telescope} surface — no new optic
   * primitives, no Iso composition beyond the base.
   */
  static <S, T> Iso<S, T> wrap(
    final Iso<S, T> base,
    final List<Mapping<?, ?>> fixups,
    final Reflective srcRefl,
    final Class<S> source
  ) {
    return Iso.of(
      s -> applyForward(base.to(s), s, fixups),
      t -> applyBackward(base.from(t), t, fixups, srcRefl, source)
    );
  }

  @SuppressWarnings("unchecked")
  private static <S, T> T applyForward(final T initial, final S s, final List<Mapping<?, ?>> fixups) {
    T t = initial;
    for (final var rawFx : fixups) {
      // Conditional<A, B>(predicate, inner) gates the inner row's forward effect by the source
      // predicate. When the predicate rejects the source, skip the row entirely — the target
      // field keeps whatever the base structural Iso produced. Predicate cast widens the
      // upper-bound wildcard `? super A` against the type-erased source `s`; safe because the
      // mapper's Class<A> verifies s at runtime through the surrounding forward(...) entry.
      final Mapping<?, ?> fx;
      if (rawFx instanceof Conditional<?, ?> cond) {
        final var predicate = (Predicate<Object>) cond.predicate();
        final boolean accepted;
        try {
          accepted = predicate.test(s);
        } catch (final Throwable predicateFailure) {
          // Decorate user-predicate exceptions (including Errors like StackOverflowError on a
          // self-recursive predicate, AssertionError on a `assert` in the body, and
          // NoClassDefFoundError) with the row's inner-kind + source field breadcrumb so the
          // failure points at the user's when(...) site, not at an opaque applyForward stack
          // frame. Matches the self-diagnosing style of Mapping.zip's cardinality check below.
          // Widened from RuntimeException to Throwable because the original catch let Errors
          // propagate raw — the breadcrumb is even more valuable for those.
          final var inner = cond.inner();
          final var innerField = inner.sourceField() == null ? "<telescope>" : inner.sourceField();
          // Include the failure CLASS name — predicate.test() commonly throws NPE on null
          // navigation, where getMessage() returns null and "Predicate failure: null" tells the
          // user nothing. The class name (NullPointerException, ClassCastException, etc.) carries
          // the actionable signal even when the message is null.
          final var failureType = predicateFailure.getClass().getSimpleName();
          final var failureMsg = predicateFailure.getMessage();
          throw new IllegalStateException(
            "Mapping.when(...) predicate threw — inner=" +
              inner.getClass().getSimpleName() +
              " (sourceField=" +
              innerField +
              "). Predicate failure: " +
              failureType +
              (failureMsg == null ? "" : ": " + failureMsg),
            predicateFailure
          );
        }
        if (!accepted) continue;
        fx = cond.inner();
      } else {
        fx = rawFx;
      }
      // Exhaustive over the sealed row family: a new permit must declare its forward effect here
      // or the compiler rejects the switch — a telescope-shaped row can no longer be silently
      // skipped by this dispatch. Structural rows never reach telescopeFixups (populateIso routes
      // them to the field-claim tail), so their branches are fail-fast routing guards, not logic.
      t = switch (fx) {
        case TelescopeTo<?, ?, ?> r -> {
          final var srcAcc = (Telescope.Accessor<S, Object>) r.srcAccessor();
          final var tgtT = (Telescope<T, Object>) r.targetTelescope();
          yield tgtT.set(t, srcAcc.apply(s));
        }
        case FromTelescopeTo<?, ?, ?> r -> {
          final var srcT = (Telescope<S, Object>) r.sourceTelescope();
          // The target side is a flat accessor; we need to rebuild t with the named target
          // field
          // overridden by srcT.read(s). Delegate to overrideTargetField, which uses the target
          // Reflective.construct the same way the source-side path does in applyBackward.
          yield overrideTargetField(t, r, srcT, s);
        }
        case TelescopeToTelescope<?, ?, ?> r -> {
          final var srcT = (Telescope<S, Object>) r.sourceTelescope();
          final var tgtT = (Telescope<T, Object>) r.targetTelescope();
          if (r.kind() == TelescopeToTelescope.Kind.ZIP) {
            final var values = srcT.toList(s);
            final var targetCount = tgtT.count(t);
            if (values.size() != targetCount) throw new IllegalStateException(
              "Mapping.zip: source has " +
                values.size() +
                " focus(es), target has " +
                targetCount +
                " — cardinality must match for positional zip."
            );
            yield tgtT.updateIndexed(t, (i, _ignored) -> values.get(i));
          }
          // Lenient: when the source path resolves to an empty focus (null intermediate in a
          // chained bean read, or an Affine miss further down the path), write null to the
          // target
          // field rather than throwing. Downstream type-default handling — where configured —
          // takes over from there.
          yield tgtT.set(t, srcT.find(s).orElse(null));
        }
        case Constant<?, ?, ?> r -> {
          final var tgtT = (Telescope<T, Object>) r.targetTelescope();
          yield tgtT.set(t, r.value());
        }
        case Compute<?, ?, ?> r -> {
          final var tgtT = (Telescope<T, Object>) r.targetTelescope();
          yield tgtT.set(t, r.supplier().get());
        }
        case Conditional<?, ?> __ -> throw new IllegalStateException(
          "Conditional row survived peeling — routing regression in applyForward"
        );
        case SameTypedTo<?, ?, ?> __ -> throw structuralInFixups(__);
        case TypedTransformTo<?, ?, ?, ?> __ -> throw structuralInFixups(__);
        case ForwardOnlyTransformTo<?, ?, ?, ?> __ -> throw structuralInFixups(__);
        case Via<?, ?> __ -> throw structuralInFixups(__);
        case Drop<?, ?, ?> __ -> throw structuralInFixups(__);
      };
    }
    return t;
  }

  /**
   * Fail-fast guard for structural rows reaching the telescope-fixup dispatch — a populateIso
   * routing regression.
   */
  private static IllegalStateException structuralInFixups(final Mapping<?, ?> row) {
    return new IllegalStateException(
      row.getClass().getSimpleName() + " is a structural row and cannot reach the telescope-fixup dispatch"
    );
  }

  @SuppressWarnings("unchecked")
  private static <S, T> S applyBackward(
    final S baseS,
    final T t,
    final List<Mapping<?, ?>> fixups,
    final Reflective srcRefl,
    final Class<S> source
  ) {
    // Collect per-field override values keyed by normalized source field name; the rebuild reads
    // through srcRefl.construct and substitutes our overrides per name. Telescope-source fixups
    // (FromTelescopeTo, TelescopeToTelescope) don't have a top-level source field —
    // they apply via srcT.set on the rebuilt baseS, after the name-keyed rebuild finishes.
    final var fieldOverrides = new HashMap<String, Object>();
    for (final var fx : fixups) {
      // Exhaustive over the sealed family: every permit declares its backward role in this pass
      // explicitly — an override contribution, a documented skip, or a fail-fast routing guard.
      switch (fx) {
        case TelescopeTo<?, ?, ?> r -> {
          final var tgtT = (Telescope<T, Object>) r.targetTelescope();
          fieldOverrides.put(srcRefl.normalize(r.sourceField()), tgtT.read(t));
        }
        // Conditional rows are forward-only by design — same retraction semantics as Constant /
        // Compute. The source rebuild leaves the corresponding source field at the baseS value;
        // forward(backward(t)) is intentionally asymmetric for predicate-gated rows.
        case Conditional<?, ?> __ -> {
        }
        // Telescope-source fixups apply in the overlay pass after the name-keyed rebuild.
        case FromTelescopeTo<?, ?, ?> __ -> {
        }
        case TelescopeToTelescope<?, ?, ?> __ -> {
        }
        // Forward-only injections: the source rebuild ignores their slots entirely.
        case Constant<?, ?, ?> __ -> {
        }
        case Compute<?, ?, ?> __ -> {
        }
        case SameTypedTo<?, ?, ?> __ -> throw structuralInFixups(__);
        case TypedTransformTo<?, ?, ?, ?> __ -> throw structuralInFixups(__);
        case ForwardOnlyTransformTo<?, ?, ?, ?> __ -> throw structuralInFixups(__);
        case Via<?, ?> __ -> throw structuralInFixups(__);
        case Drop<?, ?, ?> __ -> throw structuralInFixups(__);
      }
    }
    S s = (S) srcRefl.construct(source, name ->
      fieldOverrides.containsKey(name) ? fieldOverrides.get(name) : srcRefl.read(baseS, name)
    );
    // Telescope-source fixups overlay AFTER the name-keyed rebuild, via srcT.set on s.
    for (final var fx : fixups) {
      // Same exhaustive discipline as the override pass above.
      s = switch (fx) {
        case FromTelescopeTo<?, ?, ?> r -> {
          final var srcT = (Telescope<S, Object>) r.sourceTelescope();
          final var tgtAcc = (Telescope.Accessor<T, Object>) r.tgtAccessor();
          yield srcT.set(s, tgtAcc.apply(t));
        }
        case TelescopeToTelescope<?, ?, ?> r -> {
          final var srcT = (Telescope<S, Object>) r.sourceTelescope();
          final var tgtT = (Telescope<T, Object>) r.targetTelescope();
          if (r.kind() == TelescopeToTelescope.Kind.ZIP) {
            final var values = tgtT.toList(t);
            final var sourceCount = srcT.count(s);
            if (values.size() != sourceCount) throw new IllegalStateException(
              "Mapping.zip: target has " +
                values.size() +
                " focus(es), source has " +
                sourceCount +
                " — cardinality must match for positional zip."
            );
            yield srcT.updateIndexed(s, (i, _ignored) -> values.get(i));
          }
          yield srcT.set(s, tgtT.read(t));
        }
        // TelescopeTo already applied through fieldOverrides in the rebuild above.
        case TelescopeTo<?, ?, ?> __ -> s;
        // Forward-only by design (see the override pass).
        case Conditional<?, ?> __ -> s;
        case Constant<?, ?, ?> __ -> s;
        case Compute<?, ?, ?> __ -> s;
        case SameTypedTo<?, ?, ?> __ -> throw structuralInFixups(__);
        case TypedTransformTo<?, ?, ?, ?> __ -> throw structuralInFixups(__);
        case ForwardOnlyTransformTo<?, ?, ?, ?> __ -> throw structuralInFixups(__);
        case Via<?, ?> __ -> throw structuralInFixups(__);
        case Drop<?, ?, ?> __ -> throw structuralInFixups(__);
      };
    }
    return s;
  }

  /**
   * Forward overlay for {@link FromTelescopeTo} — rebuild the target with the named target field
   * overridden by {@code srcTelescope.read(s)}. We can't construct a typed one-hop Telescope on the
   * target side without knowing T's runtime class up-front (generics erased), so we use the target
   * Reflective via the cached structural iso the same way the source-side path does.
   */
  @SuppressWarnings("unchecked")
  private static <S, T> T overrideTargetField(
    final T t,
    final FromTelescopeTo<?, ?, ?> r,
    final Telescope<S, Object> srcT,
    final S s
  ) {
    final var tgtClass = (Class<T>) t.getClass();
    final var tgtRefl = Reflective.of(tgtClass);
    final var tgtField = tgtRefl.normalize(r.targetField());
    // Lenient: when the source path resolves to an empty focus (null intermediate in a chained
    // bean read, or an Affine miss further down the path), rebuild proceeds with null in the
    // target field rather than aborting the mapper with NoSuchElementException. Downstream type-
    // default handling — where configured — takes over from there.
    final var newValue = srcT.find(s).orElse(null);
    return (T) tgtRefl.construct(tgtClass, name -> name.equals(tgtField) ? newValue : tgtRefl.read(t, name));
  }
}
