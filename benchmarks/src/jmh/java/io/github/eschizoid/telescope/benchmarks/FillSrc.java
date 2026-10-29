package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Compute;
import io.github.eschizoid.telescope.annotations.Constant;
import io.github.eschizoid.telescope.annotations.Default;

/**
 * The source {@link FillRowBenchmark} converts into {@link FillTgt} through a {@code @Bridge} whose
 * attributes fill a property the source does not have from a literal, another from {@link
 * FillStamp}, and the region from a default where the source holds none.
 *
 * @param id carried across by name
 * @param region carried across, or {@code "EMEA"} where null
 */
@Bridge(
  value = FillTgt.class,
  defaults = @Default(field = "region", value = "EMEA"),
  constants = @Constant(field = "channel", value = "API"),
  computes = @Compute(field = "stamp", using = FillStamp.class)
)
public record FillSrc(String id, String region) {}
