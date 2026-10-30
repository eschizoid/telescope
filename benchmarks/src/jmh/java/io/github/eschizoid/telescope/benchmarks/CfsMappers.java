package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.Telescope.mapper;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * The generated mapper {@link ComputeFromSourceBenchmark} declares on a field, over its source
 * with nothing computed. This baseline has no row filling a property from the whole source.
 */
public final class CfsMappers {

  private CfsMappers() {}

  @Bridge
  public static final Mapper<CfsPerson, CfsPlain> PLAIN = mapper(CfsPerson.class, CfsPlain.class);
}
