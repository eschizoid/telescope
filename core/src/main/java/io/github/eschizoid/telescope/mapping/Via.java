package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import io.github.eschizoid.telescope.internal.optics.Iso;

/**
 * Nested-mapper correspondence row from {@link Mapping#via(Accessor, Accessor, Mapper)}. The
 * contributed leaf-level {@link Iso} wraps the nested {@link Mapper}'s forward/backward pair —
 * preserving any custom rules the user baked into that mapper.
 *
 * <p>Package-private — users construct via {@link Mapping#via(Accessor, Accessor, Mapper)} and
 * never see this type at the call site.
 */
record Via<A, B, X, Y>(Accessor<A, X> src, Accessor<B, Y> tgt, Mapper<X, Y> nested) implements Mapping<A, B> {
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
  public Iso<X, Y> fieldIso() {
    return Iso.of(nested::forward, nested::backward);
  }
}
