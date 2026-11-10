package io.github.telescopeorganization.telescope.benchmarks;

import io.github.telescopeorganization.telescope.annotations.Focus;

/** Annotated root of the {@link HolderDispatchBenchmark} deep-mapping source tree. */
@Focus
public record BenchHolderSrc(String name, BenchHolderDeptSrc department) {}
