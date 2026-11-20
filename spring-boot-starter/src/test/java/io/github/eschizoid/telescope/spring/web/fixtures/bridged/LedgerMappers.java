package io.github.eschizoid.telescope.spring.web.fixtures.bridged;

import static io.github.eschizoid.telescope.Telescope.mapperForward;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.spring.web.fixtures.Ledger;
import io.github.eschizoid.telescope.spring.web.fixtures.Unrelated;

/**
 * A {@code @Bridge} field for a pair whose components share no name. Its bridge is generated in
 * this package, away from {@link Ledger}, so the forward mapping finds it only through the provider
 * the processor registers.
 */
public final class LedgerMappers {

  @Bridge
  public static final ForwardMapper<Ledger, Unrelated> UNRELATED = mapperForward(
    Ledger.class,
    Unrelated.class,
    to(Ledger::id, Unrelated::zzz)
  );

  private LedgerMappers() {}
}
