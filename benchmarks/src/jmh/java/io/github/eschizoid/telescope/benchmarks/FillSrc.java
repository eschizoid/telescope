package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.toOrElse;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * The source {@link FillRowBenchmark} converts into {@link FillTgt} through a {@code @Bridge} field
 * whose rows fill a property the source does not have from a literal, another from {@link
 * FillStamp}, and the region from a default where the source holds none.
 *
 * @param id carried across by name
 * @param region carried across, or {@code "EMEA"} where null
 */
public record FillSrc(String id, String region) {
  @Bridge
  public static final Mapper<FillSrc, FillTgt> TGT = mapper(
    FillSrc.class,
    FillTgt.class,
    toOrElse(FillSrc::region, FillTgt::region, "EMEA"),
    constant(FillTgt::channel, "API"),
    compute(FillTgt::stamp, FillStamp.INSTANCE)
  );
}
