package io.github.eschizoid.telescope.jpa.fixtures;

import static io.github.eschizoid.telescope.Telescope.edits;
import static io.github.eschizoid.telescope.mapping.Mapping.compute;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.EditMapper;

/**
 * The edit mappers that write a cart in place, compiled into bridges by {@code telescope-codegen}.
 * The lines convert through an edit mapper of their own, which prices each one from the catalog,
 * and no row names the identifiers or the back-reference.
 */
public final class CartMappers {

  private CartMappers() {}

  /** A PATCH of a cart: {@code EDITS.patchInto(cart, changes)}. */
  @Bridge
  public static final EditMapper<CartChanges, Cart> EDITS = edits(CartChanges.class, Cart.class);

  /** A line as an edit sends it, priced on the way in. */
  @Bridge
  public static final EditMapper<LineChanges, CartLine> LINE = edits(
    LineChanges.class,
    CartLine.class,
    compute(CartLine::getUnitPrice, CartPrices::of)
  );
}
