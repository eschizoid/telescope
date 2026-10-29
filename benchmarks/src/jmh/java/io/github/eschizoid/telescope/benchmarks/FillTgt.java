package io.github.eschizoid.telescope.benchmarks;

/**
 * The target {@link FillRowBenchmark} fills from {@link FillSrc}.
 *
 * @param id read from the source
 * @param region read from the source, or its default
 * @param channel a literal
 * @param stamp the value {@link FillStamp} gives
 */
public record FillTgt(String id, String region, String channel, String stamp) {}
