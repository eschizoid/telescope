package io.github.eschizoid.telescope.demo.order;

import io.github.eschizoid.telescope.demo.api.NewLine;

/**
 * An order line as a PATCH sends it, and back: {@link CatalogLines} in the direction a mapper from
 * the order reads it. A line the client sends is priced from the catalog, never from the request.
 */
public final class LineEdits {

  private LineEdits() {}

  public static NewLine forward(final OrderLine line) {
    return CatalogLines.backward(line);
  }

  public static OrderLine backward(final NewLine item) {
    return CatalogLines.forward(item);
  }
}
