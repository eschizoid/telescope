package io.github.telescopeorganization.telescope.benchmarks;

import java.util.List;

/** The source {@link BuiltInConversionBenchmark} converts: every value held as text. */
public record BicOrder(String id, String total, String status, String paid, String quantity, List<String> tags) {}
