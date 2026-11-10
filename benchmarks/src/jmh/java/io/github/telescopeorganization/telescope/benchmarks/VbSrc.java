package io.github.telescopeorganization.telescope.benchmarks;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.via;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;
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
