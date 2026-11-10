package io.github.telescopeorganization.telescope.benchmarks;

import io.github.telescopeorganization.telescope.annotations.Focus;

/** Target-side mirror of {@link BenchHolderAddressSrc} — same components, different type. */
@Focus
public record BenchHolderAddressTgt(String city, String zip) {}
