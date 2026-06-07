package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Focus;

/** Target-side mirror of {@link BenchHolderAddressSrc} — same components, different type. */
@Focus
public record BenchHolderAddressTgt(String city, String zip) {}
