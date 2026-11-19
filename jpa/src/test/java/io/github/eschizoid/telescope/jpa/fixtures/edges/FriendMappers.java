package io.github.eschizoid.telescope.jpa.fixtures.edges;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/** The mappers that write an alpha and its cyclic friends in place: generated, and runtime. */
public final class FriendMappers {

  private FriendMappers() {}

  /** Compiled by {@code telescope-codegen}. */
  @Bridge
  public static final Mapper<AlphaForm, Alpha> GEN = Telescope.mapper(AlphaForm.class, Alpha.class);

  /** Built at run time. */
  public static final Mapper<AlphaEdit, Alpha> RT = Telescope.mapper(AlphaEdit.class, Alpha.class);
}
