package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.ViaMapper;

/**
 * A record whose {@code a} a mapper converts backward from an {@code Integer} into an {@code int},
 * and whose {@code b} a mapper converts forward from an {@code Integer} into the target's {@code
 * int}.
 *
 * @param a written back from the target's {@code Integer}
 * @param b converted forward into the target's {@code int}
 */
@Bridge(
  value = BenchWrapTgt.class,
  viaMappers = {
    @ViaMapper(field = "a", using = BenchWrapAMap.class), @ViaMapper(field = "b", using = BenchWrapBMap.class),
  }
)
public record BenchWrapSrc(int a, Integer b) {}
