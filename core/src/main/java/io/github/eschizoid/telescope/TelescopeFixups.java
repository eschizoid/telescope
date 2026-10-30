package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.internal.Reflective;
import io.github.eschizoid.telescope.internal.optics.Iso;
import io.github.eschizoid.telescope.internal.pairing.PairingMessages;
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
import io.github.eschizoid.telescope.mapping.ViaClass;
import io.github.eschizoid.telescope.mapping.ViaMethods;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
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
   * A {@code when} row's predicate behind the check its inner row's source path runs on each source
   * it is given. The check runs first and outside the predicate's diagnostic, so a source the path
   * cannot read fails with the path's own message, as the bare row does, before a predicate typed
   * by the path's root class sees it.
   */
  record SourceChecked(Function<Object, Object> given, Predicate<Object> predicate) implements Predicate<Object> {
    @Override
    public boolean test(final Object source) {
      return predicate.test(source);
    }
  }

  /**
   * Compose post-fixups on top of the base {@link Iso} produced by {@code DeepMap}'s structural
   * assembly. Four telescope-based row shapes route through this single wrapper; each contributes a
   * forward overlay and a backward overlay built from the lattice's public {@code Telescope.set} /
   * {@code Telescope.read} (and {@code toList} / {@code updateIndexed} for the {@link
   * TelescopeToTelescope.Kind#ZIP} case).
   *
   * <ul>
   *   <li>{@link TelescopeTo} (flat src → nested tgt): forward writes {@code srcAcc.apply(s)}
   *       through the target path; backward rebuilds {@code s} with {@code sourceField} read
   *       through it.
   *   <li>{@link FromTelescopeTo} (nested src → flat tgt): forward rebuilds {@code t} with {@code
   *       targetField} read through the source path; backward writes {@code tgtAcc.apply(t)}
   *       through it.
   *   <li>{@link TelescopeToTelescope} with {@link TelescopeToTelescope.Kind#BROADCAST} (nested ↔
   *       nested, broadcast): forward writes the source path's value through the target path;
   *       backward the mirror. When either side is many-focus the lattice's intrinsic broadcast /
   *       first-focus semantics apply — no extra machinery here.
   *   <li>{@link TelescopeToTelescope} with {@link TelescopeToTelescope.Kind#ZIP} (nested ↔ nested,
   *       positional N:N): forward reads {@code srcT.toList(s)} and writes positionally via {@code
   *       tgtT.updateIndexed(t, ...)} with cardinality enforcement; backward mirrors.
   * </ul>
   *
   * <p>A path that steps through properties alone is read and written through {@link PathWrites},
   * which reads a null object on the way as null and builds one to write a value that is not null
   * through, as the generated bridge does. Every other path goes through the lattice's public
   * {@link Telescope} surface.
   */
  static <S, T> Iso<S, T> wrap(
    final Iso<S, T> base,
    final List<Mapping<?, ?>> fixups,
    final Reflective srcRefl,
    final Class<S> source,
    final Reflective tgtRefl,
    final Class<T> target
  ) {
    final var plans = new Plans(new IdentityHashMap<>(), new IdentityHashMap<>(), tgtRefl);
    for (final var raw : fixups) {
      final Mapping<?, ?> fx = raw instanceof Conditional<?, ?> c ? c.inner() : raw;
      final Telescope<?, ?> read = switch (fx) {
        case FromTelescopeTo<?, ?, ?> r -> r.sourceTelescope();
        case TelescopeToTelescope<?, ?, ?> r -> r.kind() == TelescopeToTelescope.Kind.BROADCAST
          ? r.sourceTelescope()
          : null;
        default -> null;
      };
      final Telescope<?, ?> written = switch (fx) {
        case TelescopeTo<?, ?, ?> r -> r.targetTelescope();
        case TelescopeToTelescope<?, ?, ?> r -> r.kind() == TelescopeToTelescope.Kind.BROADCAST
          ? r.targetTelescope()
          : null;
        default -> null;
      };
      final var sourcePlan = read == null ? null : PathWrites.plan(read, source);
      if (sourcePlan != null) plans.source().put(fx, sourcePlan);
      final var targetPlan = written == null ? null : PathWrites.plan(written, target);
      if (targetPlan != null) plans.target().put(fx, targetPlan);
    }
    return Iso.of(
      s -> applyForward(base.to(s), s, fixups, plans),
      t -> applyBackward(base.from(t), t, fixups, srcRefl, source, plans)
    );
  }

  /**
   * The plans of the rows whose paths step through properties alone, by row, for the path each
   * reads or writes on the source and on the target, and the reflective the target is rebuilt
   * through.
   */
  private record Plans(
    Map<Mapping<?, ?>, PathWrites.Plan> source,
    Map<Mapping<?, ?>, PathWrites.Plan> target,
    Reflective tgtRefl
  ) {}

  /**
   * How patch reads a partial's value for a {@link TelescopeTo} row: through the target path, a
   * null object on the way read as null, which patch takes as no value to lay over the source.
   */
  @SuppressWarnings("unchecked")
  static Function<Object, Object> patchRead(final TelescopeTo<?, ?, ?> row, final Class<?> target) {
    final var plan = PathWrites.plan(row.targetTelescope(), target);
    final var path = (Telescope<Object, Object>) row.targetTelescope();
    return plan != null ? partial -> PathWrites.read(plan, partial) : partial -> path.find(partial).orElse(null);
  }

  /**
   * What patch lays over a source rebuilt from {@code base} for a {@link FromTelescopeTo} row whose
   * source path steps through properties alone: the partial's value written through the path, as
   * backward writes it. A path of any other shape is set through the lattice, as backward sets it.
   */
  @SuppressWarnings("unchecked")
  static BiFunction<Object, Object, Object> patchOverlay(
    final FromTelescopeTo<?, ?, ?> row,
    final Class<?> source,
    final Reflective srcRefl
  ) {
    final var plan = PathWrites.plan(row.sourceTelescope(), source);
    final var path = (Telescope<Object, Object>) row.sourceTelescope();
    return plan != null ? (s, value) -> PathWrites.write(plan, s, value, srcRefl) : (s, value) -> path.set(s, value);
  }

  @SuppressWarnings("unchecked")
  private static <S, T> T applyForward(
    final T initial,
    final S s,
    final List<Mapping<?, ?>> fixups,
    final Plans plans
  ) {
    T t = initial;
    for (final var rawFx : fixups) {
      // Conditional<A, B>(predicate, inner) gates the inner row's forward effect by the source
      // predicate. When the predicate rejects the source, skip the row entirely — the target
      // field keeps whatever the base structural Iso produced. The row is keyed to the pair its
      // inner row is keyed to, so the predicate receives the same `s` the inner row reads.
      final Mapping<?, ?> fx;
      if (rawFx instanceof Conditional<?, ?> cond) {
        final var predicate = (Predicate<Object>) cond.predicate();
        if (predicate instanceof SourceChecked checked) checked.given().apply(s);
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
          final var failureType = PairingMessages.className(predicateFailure.getClass());
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
          final var plan = plans.target().get(r);
          if (plan != null) yield (T) PathWrites.write(plan, t, srcAcc.apply(s), plans.tgtRefl());
          final var tgtT = (Telescope<T, Object>) r.targetTelescope();
          yield tgtT.set(t, srcAcc.apply(s));
        }
        case FromTelescopeTo<?, ?, ?> r -> {
          final var srcT = (Telescope<S, Object>) r.sourceTelescope();
          // The target side is a flat accessor; we need to rebuild t with the named target
          // field
          // overridden by srcT.read(s). Delegate to overrideTargetField, which uses the target
          // Reflective.construct the same way the source-side path does in applyBackward.
          yield overrideTargetField(t, r, srcT, s, plans.source().get(r));
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
          // A source path that resolves to no value, through a null object on the way or an
          // Affine miss, writes null rather than throwing.
          final var sourcePlan = plans.source().get(r);
          final var value = sourcePlan != null ? PathWrites.read(sourcePlan, s) : srcT.find(s).orElse(null);
          final var targetPlan = plans.target().get(r);
          if (targetPlan != null) yield (T) PathWrites.write(targetPlan, t, value, plans.tgtRefl());
          yield tgtT.set(t, value);
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
        case ViaMethods<?, ?> __ -> throw structuralInFixups(__);
        case ViaClass<?, ?> __ -> throw structuralInFixups(__);
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
    final Class<S> source,
    final Plans plans
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
          // A target path that resolves to no value reads as null, which a primitive record
          // component takes as its default.
          final var plan = plans.target().get(r);
          final var tgtT = (Telescope<T, Object>) r.targetTelescope();
          final var value = plan != null ? PathWrites.read(plan, t) : tgtT.find(t).orElse(null);
          final var field = srcRefl.normalize(r.sourceField());
          fieldOverrides.put(field, value != null ? value : PathWrites.nullValue(source, field));
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
        case ViaMethods<?, ?> __ -> throw structuralInFixups(__);
        case ViaClass<?, ?> __ -> throw structuralInFixups(__);
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
          final var tgtAcc = (Telescope.Accessor<T, Object>) r.tgtAccessor();
          final var plan = plans.source().get(r);
          if (plan != null) yield (S) PathWrites.write(plan, s, tgtAcc.apply(t), srcRefl);
          final var srcT = (Telescope<S, Object>) r.sourceTelescope();
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
          final var targetPlan = plans.target().get(r);
          final var value = targetPlan != null ? PathWrites.read(targetPlan, t) : tgtT.find(t).orElse(null);
          final var sourcePlan = plans.source().get(r);
          if (sourcePlan != null) yield (S) PathWrites.write(sourcePlan, s, value, srcRefl);
          yield srcT.set(s, value);
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
        case ViaMethods<?, ?> __ -> throw structuralInFixups(__);
        case ViaClass<?, ?> __ -> throw structuralInFixups(__);
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
    final S s,
    final PathWrites.Plan plan
  ) {
    final var tgtClass = (Class<T>) t.getClass();
    final var tgtRefl = Reflective.of(tgtClass);
    final var tgtField = tgtRefl.normalize(r.targetField());
    // A path that resolves to no value, through a null object on the way or an Affine miss, writes
    // the target property's null value, as a null source value does: the default of a primitive
    // record component, and null otherwise, which a bean's write strategy takes as it takes a null.
    final var newValue = plan != null ? PathWrites.read(plan, s) : srcT.find(s).orElse(null);
    final var written = newValue != null ? newValue : PathWrites.nullValue(tgtClass, tgtField);
    return (T) tgtRefl.construct(tgtClass, name -> name.equals(tgtField) ? written : tgtRefl.read(t, name));
  }
}
