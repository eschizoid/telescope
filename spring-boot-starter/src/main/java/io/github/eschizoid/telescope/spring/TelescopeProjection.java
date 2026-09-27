package io.github.eschizoid.telescope.spring;

import io.github.eschizoid.telescope.conversion.MapperBuilder;

/**
 * A Spring mapper that can apply transformations before mapping. Transformers declared on {@link
 * TelescopeMapper} run first, then those registered by {@link TelescopeCustomizer} beans, in order.
 * Registration is only open while Spring constructs the bean; afterwards the transformers are
 * fixed. Transformers apply to {@link #map} and {@link #forward} only; {@link #backward} and {@link
 * #patch} use the structural mapping directly.
 *
 * @param <S> source model type
 * @param <T> target model type
 */
public interface TelescopeProjection<S, T> {
  /** Transform the source, then map it structurally to the projection target type. */
  T map(S source);

  /** Forward conversion through the generated core mapper. */
  default T forward(final S source) {
    return map(source);
  }

  /** Backward conversion through the generated core mapper. */
  S backward(T target);

  /** Sparse target overlay through the generated core mapper. */
  S patch(S source, T partial);

  /** Add typed field correspondence overrides to the generated structural mapper. */
  default void translate(final MapperBuilder<S, T> mapping) {}

  /**
   * Append a transformation and return this projection for fluent registration. Call it from a
   * {@link TelescopeCustomizer}; after the bean is constructed it throws {@link
   * IllegalStateException}.
   */
  TelescopeProjection<S, T> addTransformer(TelescopeTransformation<S, ?> transformer);
}
