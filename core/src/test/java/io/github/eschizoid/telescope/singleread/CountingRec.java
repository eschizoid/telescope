package io.github.eschizoid.telescope.singleread;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.annotations.Default;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Record fixture whose accessor counts its invocations: the {@code @Default} coalesce decides
 * null-ness and substitutes on the same single read, which only a counting accessor can prove.
 */
@Bridge(value = CountingRecTarget.class, defaults = @Default(field = "region", value = "EMEA"))
public record CountingRec(String region) {
  public static final AtomicInteger REGION_READS = new AtomicInteger();

  @Override
  public String region() {
    REGION_READS.incrementAndGet();
    return region;
  }
}
