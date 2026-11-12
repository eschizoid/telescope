package io.github.eschizoid.telescope.demo.order;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The products the shop sells, by SKU. */
public final class Catalog {

  private static final Map<String, Product> PRODUCTS = List.of(
    new Product("MUG-01", "Enamel mug", new BigDecimal("12.50"), new BigDecimal("4.10")),
    new Product("TEE-02", "Logo T-shirt", new BigDecimal("25.00"), new BigDecimal("9.00")),
    new Product("CAP-03", "Baseball cap", new BigDecimal("18.00"), new BigDecimal("6.50")),
    new Product("BAG-04", "Tote bag", new BigDecimal("15.00"), new BigDecimal("5.00"))
  )
    .stream()
    .collect(Collectors.toUnmodifiableMap(Product::sku, Function.identity()));

  private Catalog() {}

  /** The product with this SKU, or an {@link IllegalArgumentException} naming it. */
  public static Product product(final String sku) {
    final var product = PRODUCTS.get(sku);
    if (product == null) {
      throw new IllegalArgumentException("no product with SKU " + sku);
    }
    return product;
  }
}
