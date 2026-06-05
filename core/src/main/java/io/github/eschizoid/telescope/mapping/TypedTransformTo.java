package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import io.github.eschizoid.telescope.internal.optics.Iso;
import java.util.function.Function;

/**
 * Typed-transform correspondence row from {@link Mapping#to(Accessor, Accessor, Function,
 * Function)}. The contributed leaf-level {@link Iso} wraps the user-supplied forward/backward.
 *
 * <p>Package-private — users construct via {@link Mapping#to(Accessor, Accessor, Function,
 * Function)} and never see this type at the call site.
 */
record TypedTransformTo<A, B, X, Y>(
  Accessor<A, X> src,
  Accessor<B, Y> tgt,
  Function<? super X, ? extends Y> forward,
  Function<? super Y, ? extends X> backward
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

  @Override
  @SuppressWarnings("unchecked")
  public Iso<X, Y> fieldIso() {
    return Iso.of(x -> ((Function<X, Y>) forward).apply(x), y -> ((Function<Y, X>) backward).apply(y));
  }
}
