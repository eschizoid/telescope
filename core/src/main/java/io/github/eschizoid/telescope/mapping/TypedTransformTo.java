package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import java.util.function.Function;

/**
 * Typed-transform correspondence row from {@link Mapping#to(Accessor, Accessor, Function,
 * Function)}. {@code DeepMap} reads {@link #forward()} / {@link #backward()} and assembles the
 * leaf-level Iso itself, so no internal optic type appears on this record's surface.
 *
 * <p>Internal — users construct via {@link Mapping#to(Accessor, Accessor, Function, Function)}
 * and never see this type at the call site.
 */
public record TypedTransformTo<A, B, X, Y>(
  Accessor<A, X> src,
  Accessor<B, Y> tgt,
  Function<? super X, ? extends Y> forward,
  Function<? super Y, ? extends X> backward
) implements Mapping<A, B>, MappingInternals<A, B> {
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
