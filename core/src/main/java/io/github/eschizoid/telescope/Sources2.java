package io.github.eschizoid.telescope;

/**
 * Two-source tuple consumed by {@link Telescope#merge(Class, Class, Class,
 * io.github.eschizoid.telescope.mapping.MergeStep[])}. The forward-only multi-source mapper reads
 * each row's source value from either {@link #first} or {@link #second} based on which slot the row
 * was bound to via {@link io.github.eschizoid.telescope.mapping.MergeStep#first} / {@link
 * io.github.eschizoid.telescope.mapping.MergeStep#second}.
 *
 * <p>Exists as a concrete record (rather than a {@code Tuple2} generic alias) so the mapper's
 * source type is a real, named class — both for IDE inference at call sites and for {@link
 * io.github.eschizoid.telescope.conversion.Mapper#sourceClass()} introspection.
 *
 * @param <A> the first source type
 * @param <B> the second source type
 */
public record Sources2<A, B>(A first, B second) {}
