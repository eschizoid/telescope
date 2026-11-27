package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.EditMapper;

/** The generated edit mapper {@link PatchIntoBenchmark} declares on a field. */
public final class PatchIntoMappers {

  private PatchIntoMappers() {}

  @Bridge
  public static final EditMapper<PatchIntoChanges, PatchIntoEntity> CHANGES = Telescope.edits(
    PatchIntoChanges.class,
    PatchIntoEntity.class
  );
}
