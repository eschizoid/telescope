package io.github.eschizoid.telescope.benchmarks;

/** The target {@link FormattedRowBenchmark} formats into: the date and the amount as text. */
public record FmtOrderDto(String id, String shipDate, String total) {}
