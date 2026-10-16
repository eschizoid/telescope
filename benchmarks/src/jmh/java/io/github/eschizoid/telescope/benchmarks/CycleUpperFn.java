package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.conversion.BridgeFn;
import java.util.Locale;

/** Upper-cases forward and lower-cases back. */
public final class CycleUpperFn implements BridgeFn<String, String> {

  @Override
  public String forward(final String source) {
    return source == null ? null : source.toUpperCase(Locale.ROOT);
  }

  @Override
  public String backward(final String target) {
    return target == null ? null : target.toLowerCase(Locale.ROOT);
  }
}
