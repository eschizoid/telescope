package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;

/** Source 2 of {@link SharedCallSiteBenchmark}, converted by a {@code @Bridge} on the class. */
@Bridge(ShDst2.class)
public record ShSrc2(Long id, String name, int count) {}
