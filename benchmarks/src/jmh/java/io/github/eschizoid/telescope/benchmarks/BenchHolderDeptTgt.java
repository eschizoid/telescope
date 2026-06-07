package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Focus;

/** Target-side mirror of {@link BenchHolderDeptSrc}. */
@Focus
public record BenchHolderDeptTgt(String name, int headcount, BenchHolderAddressTgt address) {}
