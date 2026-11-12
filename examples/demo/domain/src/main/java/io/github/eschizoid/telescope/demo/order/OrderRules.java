package io.github.eschizoid.telescope.demo.order;

/** Questions the mappers ask about an order. */
public final class OrderRules {

  private OrderRules() {}

  /**
   * Whether the parcel has left the warehouse. The carrier assigns a tracking number when the label
   * is printed, which can be a day before the parcel is handed over, so the number is only shown
   * once the order is shipped.
   */
  public static boolean shipped(final Order order) {
    return order.getStatus() == OrderStatus.SHIPPED;
  }
}
