package io.github.eschizoid.telescope.jpa.fixtures;

import static io.github.eschizoid.telescope.Telescope.edits;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.EditMapper;

/** The edit mapper of a ledger, compiled into a bridge by telescope-codegen. */
public final class LedgerMappers {

  private LedgerMappers() {}

  @Bridge
  public static final EditMapper<LedgerSent, Ledger> LEDGER = edits(LedgerSent.class, Ledger.class);
}
