package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Focus;

/** Target-side mirror of {@link BenchHolderSrc}. */
@Focus
public record BenchHolderTgt(String name, BenchHolderDeptTgt department) {}
