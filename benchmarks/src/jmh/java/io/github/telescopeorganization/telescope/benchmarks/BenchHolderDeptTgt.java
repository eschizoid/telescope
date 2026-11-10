package io.github.telescopeorganization.telescope.benchmarks;

import io.github.telescopeorganization.telescope.annotations.Focus;

/** Target-side mirror of {@link BenchHolderDeptSrc}. */
@Focus
public record BenchHolderDeptTgt(String name, int headcount, BenchHolderAddressTgt address) {}
