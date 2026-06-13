package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import io.github.eschizoid.telescope.internal.Reflective;
import io.github.eschizoid.telescope.mapping.MergeStep;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Engine for {@link Telescope#merge(Class, Class, Class, MergeStep[])} — the two-source
 * forward-only mapper. Each {@link MergeStep} row picks its source value from the {@link
 * Sources2#first()} or {@link Sources2#second()} slot, normalizes the row's target-side accessor to
 * its component name, and the rebuild reads through {@link Reflective#construct} the same way
 * {@code DeepMap}'s name-keyed rebuild does — records via canonical constructor, beans via the
 * auto-detected write strategy. Unmapped target components fall through to {@code null} (or the
 * primitive default), matching {@code Telescope.map(...)}'s missing-source semantics.
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
    final var targetRefl = Reflective.of(target);

    final Function<Sources2<A, B>, T> forward = sources -> {
      final Map<String, Object> byName = new HashMap<>();
      for (final var step : steps) {
        if (step instanceof MergeStep.FromFirst<A, B, T, ?> ff) {
          final var srcAcc = (Function<A, Object>) (Function<?, ?>) ff.src();
          final var tgtName = targetRefl.normalize(LambdaIntrospection.methodNameOf(ff.tgt()));
          byName.put(tgtName, srcAcc.apply(sources.first()));
        } else if (step instanceof MergeStep.FromSecond<A, B, T, ?> fs) {
          final var srcAcc = (Function<B, Object>) (Function<?, ?>) fs.src();
          final var tgtName = targetRefl.normalize(LambdaIntrospection.methodNameOf(fs.tgt()));
          byName.put(tgtName, srcAcc.apply(sources.second()));
        }
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
}
