package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;

/**
 * Same-typed correspondence row from {@link Mapping#to(Accessor, Accessor)}. Both accessors carry
 * the same leaf type {@code X}; {@code DeepMap} reads the public components and contributes an
 * identity leaf-level Iso itself, so no internal optic type appears on this record's surface.
 *
 * <p>Internal — users construct via {@link Mapping#to(Accessor, Accessor)} and never see this type
 * at the call site.
 */
public record SameTypedTo<A, B, X>(
  Accessor<A, X> src,
  Accessor<B, X> tgt
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
