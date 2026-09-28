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

  /** Same as {@link #map}: runs the transformers, then maps {@code S} to {@code T}. */
  default T forward(final S source) {
    return map(source);
  }

  /** Maps {@code T} back to {@code S}. Transformers do not run in this direction. */
  S backward(T target);

  /**
   * Overlays the non-null fields of {@code partial} onto {@code source} and keeps every other field
   * of {@code source}. Transformers do not run. A projection that maps through a generated
   * {@code @Bridge} throws {@link UnsupportedOperationException}, because a bridge rebuilds the
   * whole source from {@code partial}; override {@link #translate} to map through a core {@code
   * Mapper}, which supports it.
   */
  S patch(S source, T partial);

  /**
   * Declare the mapping rows for this projection. Overriding this method, here or on any parent
   * interface, replaces the generated {@code @Bridge}: the projection then maps through a core
   * {@code Mapper} built from these rows plus same-name backfill, so the bridge's defaults, renames
   * and conversions no longer apply. Without an override, the bridge is used when one exists.
   */
  default void translate(final MapperBuilder<S, T> mapping) {}

  /**
   * Append a transformation and return this projection for fluent registration. Call it from a
   * {@link TelescopeCustomizer}; after the bean is constructed it throws {@link
   * IllegalStateException}.
   */
  TelescopeProjection<S, T> addTransformer(TelescopeTransformation<S, ?> transformer);
}
