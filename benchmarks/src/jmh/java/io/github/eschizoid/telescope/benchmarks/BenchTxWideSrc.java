package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.Telescope.mapperForward;
import static io.github.eschizoid.telescope.mapping.Mapping.toOneWay;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.ForwardMapper;

/**
 * A bean whose one value a forward-only conversion typed wider than that value fills on the target.
 */
public class BenchTxWideSrc {

  @Bridge
  public static final ForwardMapper<BenchTxWideSrc, BenchTxTgt> TGT = mapperForward(
    BenchTxWideSrc.class,
    BenchTxTgt.class,
    toOneWay(BenchTxWideSrc::getValue, BenchTxTgt::getValue, BenchTxWideFn.INSTANCE::forward)
  );

  private String value;

  public String getValue() {
    return value;
  }

  public void setValue(final String value) {
    this.value = value;
  }
}
