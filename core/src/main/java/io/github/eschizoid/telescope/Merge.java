package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import io.github.eschizoid.telescope.internal.Reflective;
import io.github.eschizoid.telescope.internal.optics.Getter;
import io.github.eschizoid.telescope.mapping.MergeStep;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Engine for {@link Telescope#merge(Class, Class, Class, MergeStep[])} — the two-source
 * forward-only mapper. Each {@link MergeStep} row picks its source value from the {@link
 * Sources2#first()} or {@link Sources2#second()} slot (explicit factories) or via build-time class
 * inference (the recommended {@link
 * MergeStep#from(io.github.eschizoid.telescope.Telescope.Accessor,
 * io.github.eschizoid.telescope.Telescope.Accessor) from(...)} factory), normalizes the row's
 * target-side accessor to its component name, and the rebuild reads through {@link
 * Reflective#construct} the same way {@code DeepMap}'s name-keyed rebuild does — records via
 * canonical constructor, beans via the auto-detected write strategy. Unmapped target components
 * fall through to {@code null} (or the primitive default), matching {@code Telescope.map(...)}'s
 * missing-source semantics.
 *
 * <p>Build-time guards (each a precise {@link IllegalArgumentException} naming the row + the
 * factory): null source/target accessor, duplicate target field across rows, and source class
 * mismatching neither slot for {@link MergeStep.FromInferred from(...)} rows.
 *
 * <p>Backward is documented as unsupported — the multi-source case has no general inverse, so the
 * backward {@link java.util.function.Function} on the produced {@link Mapper} throws.
 */
final class Merge {

  private Merge() {}

  @SuppressWarnings({ "unchecked", "rawtypes" })
  static <A, B, T> Mapper<Sources2<A, B>, T> build(
    final Class<A> sourceA,
    final Class<B> sourceB,
    final Class<T> target,
    final MergeStep<A, B, T>[] steps
  ) {
    Objects.requireNonNull(sourceA, "Telescope.merge: sourceA class is null");
    Objects.requireNonNull(sourceB, "Telescope.merge: sourceB class is null");
    Objects.requireNonNull(target, "Telescope.merge: target class is null");
    final var targetRefl = Reflective.of(target);

    // Resolve every step to a (slot, srcAccessor, tgtName) triple up front, so the per-call
    // forward function does no SerializedLambda decode and no `instanceof` dispatch.
    final var plan = new ResolvedStep[steps.length];
    final Set<String> claimedTgt = new HashSet<>();
    for (int i = 0; i < steps.length; i++) {
      final var step = steps[i];
      if (step == null) throw new IllegalArgumentException("Telescope.merge: step at index " + i + " is null");
      plan[i] = resolveStep(step, i, sourceA, sourceB, targetRefl, claimedTgt);
    }

    final Function<Sources2<A, B>, T> forward = sources -> {
      final Map<String, Object> byName = new HashMap<>(plan.length * 2);
      for (final var r : plan) {
        final Object src = r.useFirst() ? sources.first() : sources.second();
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

  /**
   * Build-time resolution per row: validate accessors, dedupe target slot, and resolve the slot
   * dispatch (explicit first/second, or class-inferred). Throws a precise IAE naming the row index
   * + the factory on every failure path so user diagnostics are self-correlating.
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static ResolvedStep resolveStep(
    final MergeStep<?, ?, ?> step,
    final int index,
    final Class<?> sourceA,
    final Class<?> sourceB,
    final Reflective targetRefl,
    final Set<String> claimedTgt
  ) {
    final boolean useFirst;
    // Lattice-routed: hold the source accessor as the lattice's read-only optic primitive
    // (Getter), not a bare Function. Per CLAUDE.md "A Function<Object, Object> field that COULD
    // have been a Getter<X, Y> is a smell" — the row's read shape lives in the lattice.
    final Getter<Object, Object> srcAccessor;
    final Telescope.Accessor<?, ?> tgtAccessor;

    if (step instanceof MergeStep.FromInferred<?, ?, ?, ?> r) {
      ensureAccessorPresent(r.src(), index, "from", "source");
      ensureAccessorPresent(r.tgt(), index, "from", "target");
      final Class<?> srcClass = LambdaIntrospection.implClassOf(r.src());
      if (srcClass.equals(sourceA)) useFirst = true;
      else if (srcClass.equals(sourceB)) useFirst = false;
      else throw new IllegalArgumentException(
        "Telescope.merge: step at index " +
          index +
          " uses from(" +
          srcClass.getSimpleName() +
          "::...) but neither sourceA (" +
          sourceA.getSimpleName() +
          ") nor sourceB (" +
          sourceB.getSimpleName() +
          ") matches. Use MergeStep.first(...) / .second(...) explicitly, or fix the source accessor."
      );
      srcAccessor = asGetter(r.src());
      tgtAccessor = r.tgt();
    } else if (step instanceof MergeStep.FromFirst<?, ?, ?, ?> r) {
      ensureAccessorPresent(r.src(), index, "first", "source");
      ensureAccessorPresent(r.tgt(), index, "first", "target");
      useFirst = true;
      srcAccessor = asGetter(r.src());
      tgtAccessor = r.tgt();
    } else if (step instanceof MergeStep.FromSecond<?, ?, ?, ?> r) {
      ensureAccessorPresent(r.src(), index, "second", "source");
      ensureAccessorPresent(r.tgt(), index, "second", "target");
      useFirst = false;
      srcAccessor = asGetter(r.src());
      tgtAccessor = r.tgt();
    } else {
      throw new IllegalStateException("unreachable: MergeStep is sealed");
    }

    final String tgtName = targetRefl.normalize(LambdaIntrospection.methodNameOf(tgtAccessor));
    if (!claimedTgt.add(tgtName)) throw new IllegalArgumentException(
      "Telescope.merge: step at index " +
        index +
        " writes target field '" +
        tgtName +
        "' which an earlier step already claimed. Each target component may be written by at most one row."
    );
    return new ResolvedStep(useFirst, srcAccessor, tgtName);
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
  private record ResolvedStep(boolean useFirst, Getter<Object, Object> srcAccessor, String tgtName) {}
}
