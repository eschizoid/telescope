package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.Telescope.mapper;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/** A bean whose one value a conversion typed as that value fills on the target. */
public class BenchTxSrc {

  @Bridge
  public static final Mapper<BenchTxSrc, BenchTxTgt> TGT = mapper(
    BenchTxSrc.class,
    BenchTxTgt.class,
    to(BenchTxSrc::getValue, BenchTxTgt::getValue, BenchTxFn.class)
  );

  private String value;

  public String getValue() {
    return value;
  }

  public void setValue(final String value) {
    this.value = value;
  }
}
