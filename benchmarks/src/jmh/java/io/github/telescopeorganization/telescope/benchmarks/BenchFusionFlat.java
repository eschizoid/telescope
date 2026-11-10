package io.github.telescopeorganization.telescope.benchmarks;

import io.github.telescopeorganization.telescope.annotations.Focus;

/** Flat six-field record for the navigator-path rows of {@code MultiEditBenchmark}. */
@Focus
public record BenchFusionFlat(String a, String b, String c, String d, int e, int f) {}
