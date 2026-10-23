package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;

/**
 * The row {@link Mapping#via(Accessor, Accessor, Class)} builds: a property converted by the static
 * {@code forward} and {@code backward} methods of a class written by hand, chosen for the
 * property's types as a {@code @ViaMapper(using = ...)} chooses them. The row holds the class
 * alone; the runtime path reads its methods when it builds a mapper from the row, and a mapper a
 * {@code @Bridge} field declares, which runs its generated bridge, never does.
 *
 * <p>Internal: users build the row with {@code via(...)} and never name this type.
 */
public record ViaClass<A, B>(Accessor<A, ?> src, Accessor<B, ?> tgt, Class<?> using) implements Mapping<A, B> {
  @Override
  public Class<A> sourceClass() {
    return LambdaIntrospection.receiverClassOf(src);
  }

  @Override
  public Class<B> targetClass() {
    return LambdaIntrospection.receiverClassOf(tgt);
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
