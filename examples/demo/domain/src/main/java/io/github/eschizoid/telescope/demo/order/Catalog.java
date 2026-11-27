package io.github.eschizoid.telescope.demo.order;

import io.github.eschizoid.telescope.demo.api.NewLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The products the shop sells, by SKU. Each app declares one as a bean, over the products it
 * stocks, and the edit mappers that price a line ask it through their {@code service(...)} rows.
 */
public class Catalog {

  private final Map<String, Product> products;

  public Catalog(final List<Product> products) {
    this.products = products.stream().collect(Collectors.toUnmodifiableMap(Product::sku, Function.identity()));
  }

  /** What the shop stocks. */
  public static List<Product> stock() {
    return List.of(
      new Product("MUG-01", "Enamel mug", new BigDecimal("12.50"), new BigDecimal("4.10")),
      new Product("TEE-02", "Logo T-shirt", new BigDecimal("25.00"), new BigDecimal("9.00")),
      new Product("CAP-03", "Baseball cap", new BigDecimal("18.00"), new BigDecimal("6.50")),
      new Product("BAG-04", "Tote bag", new BigDecimal("15.00"), new BigDecimal("5.00"))
    );
  }

  /** The name of the product a line orders. */
  public String name(final NewLine line) {
    return product(line.sku()).name();
  }

  /** The price of the product a line orders. */
  public BigDecimal price(final NewLine line) {
    return product(line.sku()).price();
  }

  /** What the shop paid for the product a line orders. */
  public BigDecimal cost(final NewLine line) {
    return product(line.sku()).cost();
  }

  /** The product with this SKU, or an {@link IllegalArgumentException} naming it. */
  public Product product(final String sku) {
    final var product = products.get(sku);
    if (product == null) throw new IllegalArgumentException("no product with SKU " + sku);
    return product;
  }
}
