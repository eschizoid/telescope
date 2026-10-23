package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.via;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * The generated mapper {@link ViaRowBenchmark} declares on a field, with a via row of two methods.
 */
public final class VbMappers {

  private VbMappers() {}

  @Bridge
  public static final Mapper<VbFieldSrc, VbTgt> ITEMS = mapper(
    VbFieldSrc.class,
    VbTgt.class,
    via(VbFieldSrc::items, VbTgt::items, VbItemMap::forward, VbItemMap::backward)
  );
}
