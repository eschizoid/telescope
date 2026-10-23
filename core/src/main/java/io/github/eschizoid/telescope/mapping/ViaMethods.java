package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope.Accessor;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.internal.LambdaIntrospection;
import java.util.Map;
import java.util.function.Function;

/**
 * The row {@link Mapping#via(Accessor, Accessor, Accessor, Accessor)} builds: a property converted
 * by two methods written by hand. The methods are held as they were given and read only when the
 * runtime path builds a mapper from the row ({@link #resolved()}), so a mapper a {@code @Bridge}
 * field declares, which runs its generated bridge, never decodes them.
 *
 * <p>Internal: users build the row with {@code via(...)} and never name this type.
 */
public record ViaMethods<A, B>(
  Accessor<A, ?> src,
  Accessor<B, ?> tgt,
  Accessor<?, ?> forward,
  Accessor<?, ?> backward
) implements Mapping<A, B> {
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

  /**
   * The {@link Via} row the two methods make: a mapper between the classes {@code forward}'s method
   * converts between, which the runtime path converts the whole property or each element through.
   *
   * @throws IllegalArgumentException if either method is not a method reference converting one
   *     value, or {@code forward}'s return cannot be read
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public Via<A, B> resolved() {
    final var conversion = LambdaIntrospection.conversionOf(forward);
    LambdaIntrospection.conversionOf(backward);
    final Function<Object, Object> there = (Function) forward;
    final Function<Object, Object> back = (Function) backward;
    final var element = Mapper.create(
      there,
      back,
      (Class<Object>) conversion.from(),
      (Class<Object>) conversion.to(),
      Map.of()
    );
    return new Via<>(src, tgt, element);
  }
}
