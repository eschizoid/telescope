package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.jackson.FromJson;
import java.math.BigDecimal;
import java.util.List;

/** The order {@link JsonBindBenchmark} reads, nested one level, with its binder generated. */
@FromJson
public record JbOrder(
  String number,
  String customerEmail,
  JbStatus status,
  long placedAt,
  boolean gift,
  BigDecimal total,
  List<JbLine> lines
) {}
