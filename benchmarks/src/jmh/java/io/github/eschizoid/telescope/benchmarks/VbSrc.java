package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.via;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;
import java.util.List;

/**
 * The source {@link ViaRowBenchmark} converts by a via row naming {@link VbItemMap} on a
 * {@code @Bridge} field it declares itself.
 */
public record VbSrc(List<VbItem> items) {
  @Bridge
  public static final Mapper<VbSrc, VbTgt> TGT = mapper(
    VbSrc.class,
    VbTgt.class,
    via(VbSrc::items, VbTgt::items, VbItemMap.class)
  );
}
