package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Focus;

/** Annotated root of the {@link HolderDispatchBenchmark} deep-mapping source tree. */
@Focus
public record BenchHolderSrc(String name, BenchHolderDeptSrc department) {}
