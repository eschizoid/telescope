package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;

/**
 * Keep-target-property row from {@link Mapping#keep(Accessor)}: a property of an edit mapper's
 * target that no operation writes. It counts as accounted for in the strict check, so the mapper
 * builds without a row filling it, and {@code into}, {@code patchInto} and {@code patch} leave the
 * value the target holds.
 *
 * <p>Internal: users construct it through {@link Mapping#keep(Accessor)} and never name this type.
 */
public record Keep<A, B, X>(Accessor<B, X> tgt) implements Mapping<A, B> {
  /** No source class: the row is keyed to the mapper's own source. */
  @Override
  public Class<A> sourceClass() {
    return null;
  }

  @Override
  public Class<B> targetClass() {
    return LambdaIntrospection.receiverClassOf(tgt);
  }

  /** No source field: a kept property reads nothing. */
  @Override
  public String sourceField() {
    return null;
  }

  @Override
  public String targetField() {
    return LambdaIntrospection.methodNameOf(tgt);
  }
}
