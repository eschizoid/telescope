package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.Telescope.mapperForward;
import static io.github.eschizoid.telescope.mapping.Mapping.toOneWay;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.ForwardMapper;

/** A bean whose one value a forward-only conversion typed as that value fills on the target. */
public class BenchTxSrc {

  @Bridge
  public static final ForwardMapper<BenchTxSrc, BenchTxTgt> TGT = mapperForward(
    BenchTxSrc.class,
    BenchTxTgt.class,
    toOneWay(BenchTxSrc::getValue, BenchTxTgt::getValue, BenchTxFn.INSTANCE::forward)
  );

  private String value;

  public String getValue() {
    return value;
  }

  public void setValue(final String value) {
    this.value = value;
  }
}
