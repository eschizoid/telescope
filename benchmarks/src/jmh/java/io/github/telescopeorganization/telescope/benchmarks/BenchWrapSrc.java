package io.github.telescopeorganization.telescope.benchmarks;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.via;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;

/**
 * A record whose {@code a} a mapper converts backward from an {@code Integer} into an {@code int},
 * and whose {@code b} a mapper converts forward from an {@code Integer} into the target's {@code
 * int}.
 *
 * @param a written back from the target's {@code Integer}
 * @param b converted forward into the target's {@code int}
 */
public record BenchWrapSrc(int a, Integer b) {
  /** The bridge {@link WrapperResultBenchmark} measures, converting each value by a via row. */
  @Bridge
  public static final Mapper<BenchWrapSrc, BenchWrapTgt> TGT = mapper(
    BenchWrapSrc.class,
    BenchWrapTgt.class,
    via(BenchWrapSrc::a, BenchWrapTgt::a, BenchWrapAMap.class),
    via(BenchWrapSrc::b, BenchWrapTgt::b, BenchWrapBMap.class)
  );
}
