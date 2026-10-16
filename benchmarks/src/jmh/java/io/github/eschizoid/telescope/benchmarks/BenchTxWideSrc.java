package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Transform;

/** A bean whose one value a transform typed wider than that value fills on the target. */
@Bridge(
  value = BenchTxTgt.class,
  transforms = @Transform(field = "value", using = BenchTxWideFn.class, forwardOnly = true)
)
public class BenchTxWideSrc {

  private String value;

  public String getValue() {
    return value;
  }

  public void setValue(final String value) {
    this.value = value;
  }
}
