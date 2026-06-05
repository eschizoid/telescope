package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import io.github.eschizoid.telescope.internal.optics.Iso;

/**
 * Same-typed correspondence row from {@link Mapping#to(Accessor, Accessor)}. Both accessors carry
 * the same leaf type {@code X}, so the contributed leaf-level {@link Iso} is {@link
 * Iso#identity()}.
 *
 * <p>Package-private — users construct via {@link Mapping#to(Accessor, Accessor)} and never see
 * this type at the call site.
 */
record SameTypedTo<A, B, X>(Accessor<A, X> src, Accessor<B, X> tgt) implements Mapping<A, B> {
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

  /** Leaf-level Iso this row contributes. Package-private — consumed by DeepMap. */
  Iso<X, X> fieldIso() {
    return Iso.identity();
  }
}
