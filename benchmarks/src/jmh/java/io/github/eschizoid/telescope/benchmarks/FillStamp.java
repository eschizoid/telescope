package io.github.eschizoid.telescope.benchmarks;

import java.util.function.Supplier;

/** The value {@link FillRowBenchmark}'s mapping computes into {@link FillTgt#stamp()}. */
public final class FillStamp implements Supplier<String> {

  /** The one instance the rows name. */
  public static final FillStamp INSTANCE = new FillStamp();

  @Override
  public String get() {
    return "stamp";
  }
}
