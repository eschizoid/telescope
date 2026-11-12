package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/** The generated mapper {@link IntoCollectionBenchmark} declares on a field. */
public final class IntoCollectionMappers {

  private IntoCollectionMappers() {}

  @Bridge
  public static final Mapper<IntoListSrc, IntoListTgt> LIST = Telescope.mapper(IntoListSrc.class, IntoListTgt.class);
}
