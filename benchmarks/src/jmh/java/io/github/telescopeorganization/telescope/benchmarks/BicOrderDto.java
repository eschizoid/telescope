package io.github.telescopeorganization.telescope.benchmarks;

import java.math.BigDecimal;
import java.util.Set;

/** The target {@link BuiltInConversionBenchmark} converts into: each value as its own type. */
public record BicOrderDto(long id, BigDecimal total, BicStatus status, boolean paid, int quantity, Set<Long> tags) {}
