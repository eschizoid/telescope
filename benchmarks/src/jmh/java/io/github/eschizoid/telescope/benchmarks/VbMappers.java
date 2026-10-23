package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.via;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * The generated mappers {@link ViaRowBenchmark} declares on fields: one with a via row of two
 * methods, one with a via row naming their class.
 */
public final class VbMappers {

  private VbMappers() {}

  @Bridge
  public static final Mapper<VbFieldSrc, VbTgt> ITEMS = mapper(
    VbFieldSrc.class,
    VbTgt.class,
    via(VbFieldSrc::items, VbTgt::items, VbItemMap::forward, VbItemMap::backward)
  );

  @Bridge
  public static final Mapper<VbClassSrc, VbTgt> NAMED = mapper(
    VbClassSrc.class,
    VbTgt.class,
    via(VbClassSrc::items, VbTgt::items, VbItemMap.class)
  );
}
