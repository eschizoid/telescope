package io.github.telescopeorganization.telescope.benchmarks;

import io.github.telescopeorganization.telescope.annotations.Focus;

/** Target-side mirror of {@link BenchHolderSrc}. */
@Focus
public record BenchHolderTgt(String name, BenchHolderDeptTgt department) {}
