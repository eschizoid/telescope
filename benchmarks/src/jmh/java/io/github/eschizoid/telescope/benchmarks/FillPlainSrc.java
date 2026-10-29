package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;

/**
 * A pair {@link FillRowBenchmark} converts by name alone, which nothing fills: its bridge is the
 * row a run is normalised against.
 *
 * @param id carried across by name
 * @param region carried across by name
 */
@Bridge(FillPlainTgt.class)
public record FillPlainSrc(String id, String region) {}
