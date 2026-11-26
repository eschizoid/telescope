package io.github.eschizoid.telescope.collections;

import java.util.Objects;

/** A line pointing back at its order, equal to another of the same sku. */
public class MLine {

  private String sku;
  private MOrder order;

  public MLine() {}

  public String getSku() {
    return sku;
  }

  public void setSku(final String sku) {
    this.sku = sku;
  }

  public MOrder getOrder() {
    return order;
  }

  public void setOrder(final MOrder order) {
    this.order = order;
  }

  @Override
  public boolean equals(final Object other) {
    return other instanceof MLine that && Objects.equals(sku, that.sku);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(sku);
  }

  @Override
  public String toString() {
    return sku + "->" + (order == null ? null : order.getName());
  }
}
