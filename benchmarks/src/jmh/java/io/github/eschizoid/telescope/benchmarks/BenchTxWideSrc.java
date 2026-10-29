package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/** A bean whose one value a conversion typed wider than that value fills on the target. */
public class BenchTxWideSrc {

  @Bridge
  public static final Mapper<BenchTxWideSrc, BenchTxTgt> TGT = mapper(
    BenchTxWideSrc.class,
    BenchTxTgt.class,
    to(BenchTxWideSrc::getValue, BenchTxTgt::getValue, BenchTxWideFn.class)
  );

  private String value;

  public String getValue() {
    return value;
  }

  public void setValue(final String value) {
    this.value = value;
  }
}
