package io.github.eschizoid.telescope.jpa.fixtures;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.Mapper;

/**
 * The mappers that write a crate, a rack and a pallet in place, each compiled into a bridge by
 * {@code telescope-codegen}. None names how a collection is written: each one's adder, remover and
 * back-reference come from the entities' own declarations.
 */
public final class YardMappers {

  private YardMappers() {}

  /** {@code CRATE.into(crate, form)}. */
  @Bridge
  public static final Mapper<CrateForm, Crate> CRATE = Telescope.mapper(CrateForm.class, Crate.class);

  /** {@code RACK.into(rack, form)}. */
  @Bridge
  public static final Mapper<RackForm, Rack> RACK = Telescope.mapper(RackForm.class, Rack.class);

  /** {@code PALLET.into(pallet, form)}. */
  @Bridge
  public static final Mapper<PalletForm, Pallet> PALLET = Telescope.mapper(PalletForm.class, Pallet.class);

  /** {@code PALLET_PATCH.patchInto(pallet, form)}. */
  @Bridge
  public static final Mapper<Pallet, PalletForm> PALLET_PATCH = Telescope.mapper(Pallet.class, PalletForm.class);
}
