package io.github.eschizoid.telescope.spring;

/**
 * A Spring mapper that can apply transformations before mapping. Registered transformations run in
 * order. Registration updates the mapper bean for later calls; each mapping call uses one stable
 * snapshot of the registrations.
 *
 * @param <S> source model type
 * @param <T> target model type
 */
public interface TelescopeProjection<S, T> {
  /** Transform the source, then map it through the generated bridge. */
  T map(S source);

  /** Append a transformation to this mapper bean and return it for fluent registration. */
  TelescopeProjection<S, T> addTransformer(TelescopeTransformation<S, ?> transformer);
}
