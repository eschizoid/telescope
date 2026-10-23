package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.ViaMapper;
import java.util.List;

/** The source {@link ViaRowBenchmark} converts by a {@code @ViaMapper} on its class. */
@Bridge(value = VbTgt.class, viaMappers = @ViaMapper(field = "items", using = VbItemMap.class))
public record VbSrc(List<VbItem> items) {}
