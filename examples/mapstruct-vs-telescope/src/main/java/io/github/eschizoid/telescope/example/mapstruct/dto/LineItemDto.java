package io.github.eschizoid.telescope.example.mapstruct.dto;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Target-side order line — a mutable JavaBean (no-arg constructor plus getters/setters). All
 * properties are same-named — they map by recursion with no override.
 */
public final class LineItemDto {

  private String sku;
  private int quantity;
  private BigDecimal price;

  public LineItemDto() {}

  public String getSku() {
    return sku;
  }

  public void setSku(final String sku) {
    this.sku = sku;
  }

  public int getQuantity() {
    return quantity;
  }

  public void setQuantity(final int quantity) {
    this.quantity = quantity;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public void setPrice(final BigDecimal price) {
    this.price = price;
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof LineItemDto that)) {
      return false;
    }
    return quantity == that.quantity && Objects.equals(sku, that.sku) && Objects.equals(price, that.price);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sku, quantity, price);
  }

  @Override
  public String toString() {
    return "LineItemDto[sku=" + sku + ", quantity=" + quantity + ", price=" + price + "]";
  }
}
