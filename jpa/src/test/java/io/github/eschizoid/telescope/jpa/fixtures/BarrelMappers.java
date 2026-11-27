package io.github.eschizoid.telescope.jpa.fixtures;

import static io.github.eschizoid.telescope.Telescope.edits;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.EditMapper;

/** The edit mappers of a barrel and its flasks, compiled into bridges by telescope-codegen. */
public final class BarrelMappers {

  private BarrelMappers() {}

  @Bridge
  public static final EditMapper<BarrelSent, Barrel> BARREL = edits(BarrelSent.class, Barrel.class);

  @Bridge
  public static final EditMapper<FlaskSent, Flask> FLASK = edits(FlaskSent.class, Flask.class);
}
