package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import java.util.function.Function;

/**
 * Forward-only typed-transform correspondence row from {@link Mapping#toOneWay(Accessor, Accessor,
 * Function)}. Carries only the {@code forward} function — there is no backward field — so the row
 * itself cannot pretend to be a bidirectional correspondence.
 *
 * <p>Routed by {@code DeepMap} to a forward-only post-fixup: the produced row-level {@code Iso} has
 * a throwing backward at the field site, AND {@code Telescope.mapper(...)} refuses to build a
 * bidirectional {@link Mapper} when this row is present (it tells the caller to use {@link
 * Telescope#mapperForward Telescope.mapperForward} instead). The compile-time-typed forward-only
 * contract surfaces at the factory boundary rather than via a deferred runtime throw — closing the
 * partial-Iso composition concern that the original {@link TypedTransformTo} shape carried with its
 * throwing-backward field.
 *
 * <p>Internal — users construct via {@link Mapping#toOneWay(Accessor, Accessor, Function)} and
 * never see this type at the call site.
 */
public record ForwardOnlyTransformTo<A, B, X, Y>(
  Accessor<A, X> src,
  Accessor<B, Y> tgt,
  Function<? super X, ? extends Y> forward
) implements Mapping<A, B> {
  @Override
  public Class<A> sourceClass() {
    return LambdaIntrospection.implClassOf(src);
  }

  @Override
  public Class<B> targetClass() {
    return LambdaIntrospection.implClassOf(tgt);
  }

  @Override
  public String sourceField() {
    return LambdaIntrospection.methodNameOf(src);
  }

  @Override
  public String targetField() {
    return LambdaIntrospection.methodNameOf(tgt);
  }
}
