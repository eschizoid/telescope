package io.github.eschizoid.telescope.benchmarks;

/**
 * The target of {@link BenchWrapSrc}.
 *
 * @param a an {@code Integer} written back into the source's {@code int}
 * @param b an {@code int} the source's {@code Integer} is converted into
 */
public record BenchWrapTgt(Integer a, int b) {}
