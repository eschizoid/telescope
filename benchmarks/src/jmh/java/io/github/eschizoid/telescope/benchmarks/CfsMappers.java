package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.compute;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * The generated mappers {@link ComputeFromSourceBenchmark} declares on fields: one filling a
 * property from the whole source, and one over the same source with nothing computed.
 */
public final class CfsMappers {

  private CfsMappers() {}

  @Bridge
  public static final Mapper<CfsPerson, CfsCard> CARD = mapper(
    CfsPerson.class,
    CfsCard.class,
    compute(CfsCard::full, CfsNames::full)
  );

  @Bridge
  public static final Mapper<CfsPerson, CfsPlain> PLAIN = mapper(CfsPerson.class, CfsPlain.class);
}
