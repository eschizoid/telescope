package io.github.eschizoid.telescope.demo.order;

import io.github.eschizoid.telescope.demo.api.NewLine;

/**
 * Turns an item a client orders into an order line priced from the catalog, and back. A client
 * names a SKU and a quantity; the name, the price and the cost always come from the catalog. The
 * mappers that write order lines name this class in a {@code via} row.
 */
public final class CatalogLines {

  private CatalogLines() {}

  public static OrderLine forward(final NewLine item) {
    final var product = Catalog.product(item.sku());
    final var line = new OrderLine();
    line.setSku(product.sku());
    line.setName(product.name());
    line.setQuantity(item.quantity());
    line.setUnitPrice(product.price());
    line.setCostPrice(product.cost());
    return line;
  }

  public static NewLine backward(final OrderLine line) {
    return new NewLine(line.getSku(), line.getQuantity());
  }
}
