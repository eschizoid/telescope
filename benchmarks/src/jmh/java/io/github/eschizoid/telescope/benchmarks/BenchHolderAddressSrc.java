package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Focus;

/**
 * Annotated leaf record for the {@link HolderDispatchBenchmark} deep-mapping source tree. Pairs
 * with {@link BenchHolderAddressTgt} on the target side — same shape, different type, so the deep
 * factory composes a non-trivial structural {@code Iso}.
 */
@Focus
public record BenchHolderAddressSrc(String city, String zip) {}
