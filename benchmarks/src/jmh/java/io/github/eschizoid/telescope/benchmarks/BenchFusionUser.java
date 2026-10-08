package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Focus;

/** User leaf for the navigator-path tree rows of {@code MultiEditBenchmark}. */
@Focus
public record BenchFusionUser(String name, String email, int age) {}
