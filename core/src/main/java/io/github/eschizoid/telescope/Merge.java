package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import io.github.eschizoid.telescope.internal.Reflective;
import io.github.eschizoid.telescope.internal.optics.Getter;
import io.github.eschizoid.telescope.mapping.MergeStep2;
import io.github.eschizoid.telescope.mapping.MergeStep3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Engine for {@link Telescope#merge(Class, Class, Class, MergeStep2[])} and its arity-3 sibling
 * {@link Telescope#merge(Class, Class, Class, Class, MergeStep3[])} — the multi-source forward-only
 * mappers. Each row picks its source value from a {@link Sources} slot (explicit factory or
 * build-time class inference), and the rebuild reads through {@link Reflective#construct} the same
 * way {@code DeepMap}'s name-keyed rebuild does — records via canonical constructor, beans via the
 * auto-detected write strategy. Unmapped target components fall through to {@code null} (or the
 * primitive default), matching {@code Telescope.map(...)}'s missing-source semantics.
 *
 * <p>Engine is slot-indexed, not slot-named: {@link ResolvedStep#slot()} is a 0-based integer that
 * the forward function feeds to {@link Sources#slot(int)}. This keeps the dispatch loop
 * arity-generic, so adding {@code Sources4} / {@code Sources5} is a mechanical extension — the
 * dispatch carries through with no engine changes.
 *
 * <p>Build-time guards (each a precise {@link IllegalArgumentException} naming the row + the
 * factory): null source/target accessor, duplicate target field across rows, and source class
 * mismatching any declared slot for {@code from(...)} / {@code auto(...)} rows.
 *
 * <p>Backward is documented as unsupported — the multi-source case has no general inverse, so the
 * backward {@link java.util.function.Function} on the produced {@link Mapper} throws.
 */
final class Merge {

  private Merge() {}

  // ---------------------------------------------------------------------------
  // Arity 2
  // ---------------------------------------------------------------------------

  @SuppressWarnings({ "unchecked", "rawtypes" })
  static <A, B, T> Mapper<Sources2<A, B>, T> build2(
    final Class<A> sourceA,
    final Class<B> sourceB,
    final Class<T> target,
    final MergeStep2<A, B, T>[] steps
  ) {
    Objects.requireNonNull(sourceA, "Telescope.merge: sourceA class is null");
    Objects.requireNonNull(sourceB, "Telescope.merge: sourceB class is null");
    Objects.requireNonNull(target, "Telescope.merge: target class is null");
    final var targetRefl = Reflective.of(target);
    final var sourceClasses = new Class<?>[] { sourceA, sourceB };

    final var plan = new ArrayList<ResolvedStep>(steps.length);
    final Set<String> claimedTgt = new HashSet<>();
    for (int i = 0; i < steps.length; i++) {
      final var step = steps[i];
      if (step == null) throw new IllegalArgumentException("Telescope.merge: step at index " + i + " is null");
      resolveStep2(step, i, sourceClasses, target, targetRefl, claimedTgt, plan);
    }

    final Function<Sources2<A, B>, T> forward = sources -> {
      final Map<String, Object> byName = new HashMap<>(plan.size() * 2);
      for (final var r : plan) {
        final Object src = sources.slot(r.slot());
        byName.put(r.tgtName(), r.srcAccessor().get(src));
      }
      return (T) targetRefl.construct(target, byName::get);
    };

    final Function<T, Sources2<A, B>> backward = t -> {
      throw new UnsupportedOperationException(
        "Telescope.merge produces a forward-only mapper — the multi-source case has no general " +
          "inverse. Use Mapper.forward(...) only; backward/patch are unsupported."
      );
    };

    final Class<Sources2<A, B>> sourcesClass = (Class) Sources2.class;
    return Mapper.create(forward, backward, sourcesClass, target, Map.of());
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static void resolveStep2(
    final MergeStep2<?, ?, ?> step,
    final int index,
    final Class<?>[] sourceClasses,
    final Class<?> targetClass,
    final Reflective targetRefl,
    final Set<String> claimedTgt,
    final List<ResolvedStep> out
  ) {
    if (step instanceof MergeStep2.AutoSameName<?, ?, ?> r) {
      resolveAutoBackfill(r.sourceClass(), index, sourceClasses, targetClass, targetRefl, claimedTgt, out);
      return;
    }
    final int slot;
    final Getter<Object, Object> srcAccessor;
    final Telescope.Accessor<?, ?> tgtAccessor;
    if (step instanceof MergeStep2.FromInferred<?, ?, ?, ?> r) {
      ensureAccessorPresent(r.src(), index, "from", "source");
      ensureAccessorPresent(r.tgt(), index, "from", "target");
      slot = inferSlot(r.src(), index, "from", sourceClasses);
      srcAccessor = asGetter(r.src());
      tgtAccessor = r.tgt();
    } else if (step instanceof MergeStep2.FromFirst<?, ?, ?, ?> r) {
      ensureAccessorPresent(r.src(), index, "first", "source");
      ensureAccessorPresent(r.tgt(), index, "first", "target");
      slot = 0;
      srcAccessor = asGetter(r.src());
      tgtAccessor = r.tgt();
    } else if (step instanceof MergeStep2.FromSecond<?, ?, ?, ?> r) {
      ensureAccessorPresent(r.src(), index, "second", "source");
      ensureAccessorPresent(r.tgt(), index, "second", "target");
      slot = 1;
      srcAccessor = asGetter(r.src());
      tgtAccessor = r.tgt();
    } else {
      throw new IllegalStateException("unreachable: MergeStep2 is sealed");
    }
    final String tgtName = targetRefl.normalize(LambdaIntrospection.methodNameOf(tgtAccessor));
    claimTarget(tgtName, index, claimedTgt);
    out.add(new ResolvedStep(slot, srcAccessor, tgtName));
  }

  // ---------------------------------------------------------------------------
  // Arity 3
  // ---------------------------------------------------------------------------

  @SuppressWarnings({ "unchecked", "rawtypes" })
  static <A, B, C, T> Mapper<Sources3<A, B, C>, T> build3(
    final Class<A> sourceA,
    final Class<B> sourceB,
    final Class<C> sourceC,
    final Class<T> target,
    final MergeStep3<A, B, C, T>[] steps
  ) {
    Objects.requireNonNull(sourceA, "Telescope.merge: sourceA class is null");
    Objects.requireNonNull(sourceB, "Telescope.merge: sourceB class is null");
    Objects.requireNonNull(sourceC, "Telescope.merge: sourceC class is null");
    Objects.requireNonNull(target, "Telescope.merge: target class is null");
    final var targetRefl = Reflective.of(target);
    final var sourceClasses = new Class<?>[] { sourceA, sourceB, sourceC };

    final var plan = new ArrayList<ResolvedStep>(steps.length);
    final Set<String> claimedTgt = new HashSet<>();
    for (int i = 0; i < steps.length; i++) {
      final var step = steps[i];
      if (step == null) throw new IllegalArgumentException("Telescope.merge: step at index " + i + " is null");
      resolveStep3(step, i, sourceClasses, target, targetRefl, claimedTgt, plan);
    }

    final Function<Sources3<A, B, C>, T> forward = sources -> {
      final Map<String, Object> byName = new HashMap<>(plan.size() * 2);
      for (final var r : plan) {
        final Object src = sources.slot(r.slot());
        byName.put(r.tgtName(), r.srcAccessor().get(src));
      }
      return (T) targetRefl.construct(target, byName::get);
    };

    final Function<T, Sources3<A, B, C>> backward = t -> {
      throw new UnsupportedOperationException(
        "Telescope.merge produces a forward-only mapper — the multi-source case has no general " +
          "inverse. Use Mapper.forward(...) only; backward/patch are unsupported."
      );
    };

    final Class<Sources3<A, B, C>> sourcesClass = (Class) Sources3.class;
    return Mapper.create(forward, backward, sourcesClass, target, Map.of());
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static void resolveStep3(
    final MergeStep3<?, ?, ?, ?> step,
    final int index,
    final Class<?>[] sourceClasses,
    final Class<?> targetClass,
    final Reflective targetRefl,
    final Set<String> claimedTgt,
    final List<ResolvedStep> out
  ) {
    if (step instanceof MergeStep3.AutoSameName<?, ?, ?, ?> r) {
      resolveAutoBackfill(r.sourceClass(), index, sourceClasses, targetClass, targetRefl, claimedTgt, out);
      return;
    }
    final int slot;
    final Getter<Object, Object> srcAccessor;
    final Telescope.Accessor<?, ?> tgtAccessor;
    if (step instanceof MergeStep3.FromInferred<?, ?, ?, ?, ?> r) {
      ensureAccessorPresent(r.src(), index, "from", "source");
      ensureAccessorPresent(r.tgt(), index, "from", "target");
      slot = inferSlot(r.src(), index, "from", sourceClasses);
      srcAccessor = asGetter(r.src());
      tgtAccessor = r.tgt();
    } else if (step instanceof MergeStep3.FromFirst<?, ?, ?, ?, ?> r) {
      ensureAccessorPresent(r.src(), index, "first", "source");
      ensureAccessorPresent(r.tgt(), index, "first", "target");
      slot = 0;
      srcAccessor = asGetter(r.src());
      tgtAccessor = r.tgt();
    } else if (step instanceof MergeStep3.FromSecond<?, ?, ?, ?, ?> r) {
      ensureAccessorPresent(r.src(), index, "second", "source");
      ensureAccessorPresent(r.tgt(), index, "second", "target");
      slot = 1;
      srcAccessor = asGetter(r.src());
      tgtAccessor = r.tgt();
    } else if (step instanceof MergeStep3.FromThird<?, ?, ?, ?, ?> r) {
      ensureAccessorPresent(r.src(), index, "third", "source");
      ensureAccessorPresent(r.tgt(), index, "third", "target");
      slot = 2;
      srcAccessor = asGetter(r.src());
      tgtAccessor = r.tgt();
    } else {
      throw new IllegalStateException("unreachable: MergeStep3 is sealed");
    }
    final String tgtName = targetRefl.normalize(LambdaIntrospection.methodNameOf(tgtAccessor));
    claimTarget(tgtName, index, claimedTgt);
    out.add(new ResolvedStep(slot, srcAccessor, tgtName));
  }

  // ---------------------------------------------------------------------------
  // Shared helpers — arity-generic by design
  // ---------------------------------------------------------------------------

  private static int inferSlot(
    final Telescope.Accessor<?, ?> srcAccessor,
    final int index,
    final String factory,
    final Class<?>[] sourceClasses
  ) {
    final Class<?> srcClass = LambdaIntrospection.implClassOf(srcAccessor);
    for (int s = 0; s < sourceClasses.length; s++) if (srcClass.equals(sourceClasses[s])) return s;
    throw new IllegalArgumentException(
      "Telescope.merge: step at index " +
        index +
        " uses " +
        factory +
        "(" +
        srcClass.getSimpleName() +
        "::...) but none of the declared sources (" +
        Arrays.stream(sourceClasses).map(Class::getSimpleName).toList() +
        ") match. Use explicit MergeStep.first(...) / .second(...) / .third(...), or fix the source accessor."
    );
  }

  // Auto-backfill: enumerate the source class's component names, intersect with target names that
  // are still unclaimed AND that have a matching generic type, and emit a ResolvedStep per match
  // reading the source slot via a name-keyed getter. Wrong sourceClass (not in sourceClasses)
  // throws an IAE. Skipped names (type mismatch or already claimed) are silent — the user can mix
  // auto(...) with explicit rows to override per-component decisions.
  private static void resolveAutoBackfill(
    final Class<?> sourceClass,
    final int index,
    final Class<?>[] sourceClasses,
    final Class<?> targetClass,
    final Reflective targetRefl,
    final Set<String> claimedTgt,
    final List<ResolvedStep> out
  ) {
    int slot = -1;
    for (int s = 0; s < sourceClasses.length; s++) if (sourceClass.equals(sourceClasses[s])) {
      slot = s;
      break;
    }
    if (slot < 0) throw new IllegalArgumentException(
      "Telescope.merge: step at index " +
        index +
        " calls auto(" +
        sourceClass.getSimpleName() +
        ") but " +
        sourceClass.getSimpleName() +
        " is not one of the declared sources (" +
        Arrays.stream(sourceClasses).map(Class::getSimpleName).toList() +
        "). Use auto(...) with a class declared in the merge's source list."
    );
    final var sourceRefl = Reflective.of(sourceClass);
    final var srcNames = sourceRefl.names(sourceClass);
    final var tgtNames = Set.of(targetRefl.names(targetClass));
    for (final var name : srcNames) {
      if (!tgtNames.contains(name)) continue;
      if (claimedTgt.contains(name)) continue;
      // Type-match — same-name same-type backfill matches Mapping#auto() semantics. Mismatches
      // are silently skipped (the user can override with an explicit row).
      final var srcType = sourceRefl.genericType(sourceClass, name);
      final var tgtType = targetRefl.genericType(targetClass, name);
      if (srcType == null || tgtType == null || !srcType.equals(tgtType)) continue;
      claimedTgt.add(name);
      final int capturedSlot = slot;
      final String capturedName = name;
      final Getter<Object, Object> reader = src -> sourceRefl.read(src, capturedName);
      out.add(new ResolvedStep(capturedSlot, reader, capturedName));
    }
  }

  private static void claimTarget(final String tgtName, final int index, final Set<String> claimedTgt) {
    if (!claimedTgt.add(tgtName)) throw new IllegalArgumentException(
      "Telescope.merge: step at index " +
        index +
        " writes target field '" +
        tgtName +
        "' which an earlier step already claimed. Each target component may be written by at most one row."
    );
  }

  private static void ensureAccessorPresent(
    final Object accessor,
    final int index,
    final String factory,
    final String side
  ) {
    if (accessor == null) throw new IllegalArgumentException(
      "Telescope.merge: step at index " +
        index +
        " calls MergeStep." +
        factory +
        "(...) with a null " +
        side +
        " accessor."
    );
  }

  // Adapt a row accessor (an `Accessor` / `Function`) to the lattice's `Getter<Object, Object>`
  // read primitive. The dispatch at runtime is one virtual call into the same lambda body — but
  // the typed substrate now lives in the lattice, satisfying the "use Getter, not Function" rule.
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static Getter<Object, Object> asGetter(final Telescope.Accessor accessor) {
    return accessor::apply;
  }

  // ResolvedStep holds the row's per-call dispatch triple. `srcAccessor` is typed as the lattice's
  // `Getter<Object, Object>` rather than a raw `Function` — see CLAUDE.md's "Lattice-first" rule.
  // `slot` is a 0-based slot index into the Sources tuple — arity-generic dispatch.
  record ResolvedStep(int slot, Getter<Object, Object> srcAccessor, String tgtName) {}
}
