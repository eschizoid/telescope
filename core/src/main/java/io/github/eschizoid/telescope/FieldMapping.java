package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Telescope.methodNameOf;

import io.github.eschizoid.telescope.Telescope.Accessor;
import java.util.function.Function;

/**
 * Intermediate of {@link MapBuilder#field(Accessor)} — expects a {@code .to(...)} or {@code
 * .via(...)}.
 */
public final class FieldMapping<A, B, X> {

  private final MapBuilder<A, B> builder;
  private final String sourceField;

  FieldMapping(final MapBuilder<A, B> builder, final String sourceField) {
    this.builder = builder;
    this.sourceField = sourceField;
  }

  /** Complete the correspondence with a same-typed target field. */
  public MapBuilder<A, B> to(final Accessor<B, X> targetGetter) {
    return builder.link(new MapBuilder.Link(sourceField, methodNameOf(targetGetter), x -> x, y -> y));
  }

  /**
   * Complete the correspondence with a target field of a different type, supplying both directions
   * of the conversion. Keeps the overall mapping a bijection (composition-safe).
   *
   * <pre>{@code
   * .field(UserEntity::createdAt).to(UserDto::createdAtIso, Instant::toString, Instant::parse)
   * }</pre>
   */
  @SuppressWarnings("unchecked")
  public <Y> MapBuilder<A, B> to(
    final Accessor<B, Y> targetGetter,
    final Function<? super X, ? extends Y> forward,
    final Function<? super Y, ? extends X> backward
  ) {
    return builder.link(
      new MapBuilder.Link(
        sourceField,
        methodNameOf(targetGetter),
        x -> forward.apply((X) x),
        y -> backward.apply((Y) y)
      )
    );
  }

  /**
   * Map a nested record field through another {@link Mapper}. The nested mapper supplies both
   * directions, so the correspondence stays bidirectional.
   *
   * <pre>{@code
   * .field(UserEntity::address).via(UserDto::address, addressMapper)
   * }</pre>
   */
  @SuppressWarnings("unchecked")
  public <Y> MapBuilder<A, B> via(final Accessor<B, Y> targetGetter, final Mapper<X, Y> nested) {
    return builder.link(
      new MapBuilder.Link(
        sourceField,
        methodNameOf(targetGetter),
        x -> nested.forward((X) x),
        y -> nested.backward((Y) y)
      )
    );
  }
}
