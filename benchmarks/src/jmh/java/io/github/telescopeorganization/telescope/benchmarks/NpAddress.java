package io.github.telescopeorganization.telescope.benchmarks;

/**
 * The object holding the leaves {@link NestedPathBenchmark}'s paths read.
 *
 * @param city a reference leaf
 * @param zip a primitive leaf
 */
public record NpAddress(String city, int zip) {}
