package io.github.eschizoid.telescope.benchmarks;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The source {@link FormattedRowBenchmark} formats: a date and an amount. */
public record FmtOrder(String id, LocalDate shipDate, BigDecimal total) {}
