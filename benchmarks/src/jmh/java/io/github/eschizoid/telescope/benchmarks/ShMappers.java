package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * Generated mappers declared on fields, for {@link SharedCallSiteBenchmark}: four pairs of the same
 * shape, so each field's mapper is a class of its own and a call site handed all four sees four
 * receiver classes.
 */
public final class ShMappers {

  private ShMappers() {}

  @Bridge
  public static final Mapper<ShField1, ShDst1> ONE = Telescope.mapper(ShField1.class, ShDst1.class);

  @Bridge
  public static final Mapper<ShField2, ShDst2> TWO = Telescope.mapper(ShField2.class, ShDst2.class);

  @Bridge
  public static final Mapper<ShField3, ShDst3> THREE = Telescope.mapper(ShField3.class, ShDst3.class);

  @Bridge
  public static final Mapper<ShField4, ShDst4> FOUR = Telescope.mapper(ShField4.class, ShDst4.class);
}
