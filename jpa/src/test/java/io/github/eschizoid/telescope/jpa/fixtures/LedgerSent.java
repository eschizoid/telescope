package io.github.eschizoid.telescope.jpa.fixtures;

/** A ledger as a client sends it, carrying the identifier and the version it last read. */
public record LedgerSent(Long id, int version, String note) {}
