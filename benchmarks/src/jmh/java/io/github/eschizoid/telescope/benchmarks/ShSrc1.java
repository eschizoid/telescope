package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;

/** Source 1 of {@link SharedCallSiteBenchmark}, converted by a {@code @Bridge} on the class. */
@Bridge(ShDst1.class)
public record ShSrc1(Long id, String name, int count) {}
