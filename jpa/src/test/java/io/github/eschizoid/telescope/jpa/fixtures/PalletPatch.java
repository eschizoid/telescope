package io.github.eschizoid.telescope.jpa.fixtures;

import java.util.List;

/** A pallet as a PATCH sends it: a property left null is not changed. */
public record PalletPatch(Long id, String name, List<ItemPatch> sacks) {}
