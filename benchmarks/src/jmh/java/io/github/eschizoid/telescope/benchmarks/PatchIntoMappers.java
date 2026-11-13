package io.github.eschizoid.telescope.benchmarks;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/** The generated mapper {@link PatchIntoBenchmark} declares on a field. */
public final class PatchIntoMappers {

  private PatchIntoMappers() {}

  @Bridge
  public static final Mapper<PatchIntoEntity, PatchIntoChanges> CHANGES = Telescope.mapper(
    PatchIntoEntity.class,
    PatchIntoChanges.class
  );
}
