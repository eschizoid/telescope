package io.github.eschizoid.telescope.jpa.fixtures;

import java.util.List;

/** A pallet as a client sends it; a PATCH leaves out what it does not change. */
public record PalletForm(Long id, String name, List<ItemForm> sacks) {}
