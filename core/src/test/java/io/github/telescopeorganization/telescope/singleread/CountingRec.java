package io.github.telescopeorganization.telescope.singleread;

import static io.github.telescopeorganization.telescope.Telescope.mapper;
import static io.github.telescopeorganization.telescope.mapping.Mapping.toOrElse;

import io.github.telescopeorganization.telescope.annotations.Bridge;
import io.github.telescopeorganization.telescope.conversion.Mapper;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Record fixture whose accessor counts its invocations: the {@code toOrElse} coalesce decides
 * null-ness and substitutes on the same single read, which only a counting accessor can prove.
 */
public record CountingRec(String region) {
  public static final AtomicInteger REGION_READS = new AtomicInteger();

  @Bridge
  public static final Mapper<CountingRec, CountingRecTarget> TARGET = mapper(
    CountingRec.class,
    CountingRecTarget.class,
    toOrElse(CountingRec::region, CountingRecTarget::region, "EMEA")
  );

  @Override
  public String region() {
    REGION_READS.incrementAndGet();
    return region;
  }
}
