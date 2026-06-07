package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * Annotated middle-level record for the {@link HolderDispatchBenchmark} deep-mapping source tree.
 */
@Focus
public record BenchHolderDeptSrc(String name, int headcount, BenchHolderAddressSrc address) {}
