package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import io.github.eschizoid.telescope.internal.Reflective;
import io.github.eschizoid.telescope.internal.optics.Getter;
import io.github.eschizoid.telescope.mapping.MergeStep;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Engine for {@link Telescope#merge(Class, MergeStep[])} — the N-source forward-only mapper.
 * Single arity-agnostic build path: every row carries its source class (recovered via {@code
 * SerializedLambda} for explicit {@code from(...)} rows, supplied directly for {@code auto(...)}
 * rows), and the forward dispatch reads the matching value from {@link Sources#byClass(Class)} at
 * runtime.
 *
 * <p>Build-time guards (each a precise {@link IllegalArgumentException} naming the row index +
 * the factory): null source/target accessor, duplicate target field across rows.
 *
 * <p>Forward-time guard (an {@link IllegalStateException}): if the supplied {@link Sources} bag
 * lacks an entry for one of the row source classes, the failure surfaces with the missing class
 * name — distinguishing "user forgot to bind a source" from a build-time misconfiguration.
 *
 * <p>Backward is documented as unsupported — the multi-source case has no general inverse, so the
 * backward {@link Function} on the produced {@link Mapper} throws.
 */
final class Merge {

  private Merge() {}

  @SuppressWarnings({ "unchecked", "rawtypes" })
  static <T> Mapper<Sources, T> build(final Class<T> target, final MergeStep<T>[] steps) {
    Objects.requireNonNull(target, "Telescope.merge: target class is null");
    final var targetRefl = Reflective.of(target);

    final var plan = new ArrayList<ResolvedStep>(steps.length);
    final Set<String> claimedTgt = new HashSet<>();
    for (int i = 0; i < steps.length; i++) {
      final var step = steps[i];
      if (step == null) throw new IllegalArgumentException("Telescope.merge: step at index " + i + " is null");
      resolveStep(step, i, target, targetRefl, claimedTgt, plan);
    }

    final Function<Sources, T> forward = sources -> {
      final Map<String, Object> byName = new HashMap<>(plan.size() * 2);
      for (final var r : plan) {
        final Object src = sources.byClass(r.sourceClass());
        if (src == null) throw new IllegalStateException(
          "Telescope.merge: forward called without a source for class " +
            r.sourceClass().getName() +
            " (required by the row writing target field '" +
            r.tgtName() +
            "'). Sources bag contains: " +
            sources.classes().stream().map(Class::getSimpleName).toList() +
            ". Pass a value of " +
            r.sourceClass().getSimpleName() +
            " via Sources.of(...) or Sources.builder().with(...)."
        );
        byName.put(r.tgtName(), r.srcAccessor().get(src));
      }
      return (T) targetRefl.construct(target, byName::get);
    };

    final Function<T, Sources> backward = t -> {
      throw new UnsupportedOperationException(
        "Telescope.merge produces a forward-only mapper — the multi-source case has no general " +
          "inverse. Use Mapper.forward(...) only; backward/patch are unsupported."
      );
    };

    return Mapper.create(forward, backward, Sources.class, target, Map.of());
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static void resolveStep(
    final MergeStep<?> step,
    final int index,
    final Class<?> targetClass,
    final Reflective targetRefl,
    final Set<String> claimedTgt,
    final List<ResolvedStep> out
  ) {
    if (step instanceof MergeStep.AutoSameName<?> r) {
      resolveAutoBackfill(r.sourceClass(), index, targetClass, targetRefl, claimedTgt, out);
      return;
    }
    if (step instanceof MergeStep.FromInferred<?, ?> r) {
      ensureAccessorPresent(r.src(), index, "from", "source");
      ensureAccessorPresent(r.tgt(), index, "from", "target");
      final Class<?> srcClass = LambdaIntrospection.implClassOf(r.src());
      final Getter<Object, Object> srcAccessor = asGetter(r.src());
      final String tgtName = targetRefl.normalize(LambdaIntrospection.methodNameOf(r.tgt()));
      claimTarget(tgtName, index, claimedTgt);
      out.add(new ResolvedStep(srcClass, srcAccessor, tgtName));
      return;
    }
    throw new IllegalStateException("unreachable: MergeStep is sealed");
  }

  // Auto-backfill: enumerate the source class's component names, intersect with target names that
  // are still unclaimed AND that have a matching generic type, and emit a ResolvedStep per match
  // reading from the source via a name-keyed getter. Mismatches and already-claimed names are
  // silently skipped — the user can mix auto(...) with explicit rows to override per-component
  // decisions.
  private static void resolveAutoBackfill(
    final Class<?> sourceClass,
    final int index,
    final Class<?> targetClass,
    final Reflective targetRefl,
    final Set<String> claimedTgt,
    final List<ResolvedStep> out
  ) {
    Objects.requireNonNull(sourceClass, "Telescope.merge: step at index " + index + " calls auto(null)");
    final var sourceRefl = Reflective.of(sourceClass);
    final var srcNames = sourceRefl.names(sourceClass);
    final var tgtNames = Set.of(targetRefl.names(targetClass));
    for (final var name : srcNames) {
      if (!tgtNames.contains(name)) continue;
      if (claimedTgt.contains(name)) continue;
      final var srcType = sourceRefl.genericType(sourceClass, name);
      final var tgtType = targetRefl.genericType(targetClass, name);
      if (srcType == null || tgtType == null) continue; // bean introspection couldn't recover one side; skip silently
      if (!srcType.equals(tgtType)) throw new IllegalArgumentException(
        "Telescope.merge: step at index " +
          index +
          " auto(" +
          sourceClass.getSimpleName() +
          ") matched field '" +
          name +
          "' by name but types differ: source=" +
          srcType +
          ", target=" +
          tgtType +
          ". Add an explicit MergeStep.from(...) row with a converter, or rename to break the name match if this row was unintentional."
      );
      claimedTgt.add(name);
      final String capturedName = name;
      final Getter<Object, Object> reader = src -> sourceRefl.read(src, capturedName);
      out.add(new ResolvedStep(sourceClass, reader, capturedName));
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
  // `sourceClass` keys into Sources.byClass at forward time.
  record ResolvedStep(Class<?> sourceClass, Getter<Object, Object> srcAccessor, String tgtName) {}
}
