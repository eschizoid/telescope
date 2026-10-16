package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.conversion.BridgeFn;

/** A transform typed wider than the property it fills, handing each value back as it is. */
public class BenchTxWideFn implements BridgeFn<String, Object> {

  @Override
  public Object forward(final String value) {
    return value;
  }

  @Override
  public String backward(final Object value) {
    return String.valueOf(value);
  }
}
