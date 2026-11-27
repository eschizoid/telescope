package io.github.eschizoid.telescope.jpa.fixtures;

import static io.github.eschizoid.telescope.Telescope.edits;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.EditMapper;

/** An edit mapper of a barrel whose flasks convert by name, compiled by telescope-codegen. */
public final class CaskMappers {

  private CaskMappers() {}

  @Bridge
  public static final EditMapper<CaskSent, Barrel> CASK = edits(CaskSent.class, Barrel.class);
}
