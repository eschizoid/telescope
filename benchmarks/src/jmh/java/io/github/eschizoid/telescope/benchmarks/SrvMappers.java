package io.github.eschizoid.telescope.benchmarks;

import static io.github.eschizoid.telescope.Telescope.mapperForward;
import static io.github.eschizoid.telescope.mapping.Mapping.compute;
import static io.github.eschizoid.telescope.mapping.Mapping.service;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.ForwardMapper;

/**
 * The generated mappers {@link ServiceRowBenchmark} declares on fields: a row calling a static
 * method, the same row calling a service, and the pair with nothing computed.
 */
public final class SrvMappers {

  private SrvMappers() {}

  @Bridge
  public static final ForwardMapper<SrvPerson, SrvCard> STATIC = mapperForward(
    SrvPerson.class,
    SrvCard.class,
    compute(SrvCard::full, SrvNames::full)
  );

  @Bridge
  public static final ForwardMapper<SrvPerson, SrvServedCard> SERVED = mapperForward(
    SrvPerson.class,
    SrvServedCard.class,
    compute(SrvServedCard::full, service(SrvNamer::full))
  );

  @Bridge
  public static final ForwardMapper<SrvPerson, SrvPlain> PLAIN = mapperForward(SrvPerson.class, SrvPlain.class);
}
