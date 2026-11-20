package shop.warehouse;

import static io.github.eschizoid.telescope.Telescope.mapperForward;
import static io.github.eschizoid.telescope.Telescope.path;
import static io.github.eschizoid.telescope.mapping.Mapping.to;

import io.github.eschizoid.telescope.annotations.Bridge;
import io.github.eschizoid.telescope.conversion.ForwardMapper;
import io.github.eschizoid.telescope.demo.order.Customer;
import io.github.eschizoid.telescope.demo.order.Order;

/**
 * The warehouse's mapping from an order to its label. It lives in the warehouse's own package, as
 * an application's classes do, outside the telescope packages a native image initializes at build
 * time, so the image finds the bridge {@code @Bridge} generates here through its service provider
 * at run time.
 */
public final class ShippingLabels {

  @Bridge
  public static final ForwardMapper<Order, ShippingLabel> LABEL = mapperForward(
    Order.class,
    ShippingLabel.class,
    to(path(Order::getCustomer, Customer::getName), ShippingLabel::recipient)
  );

  private ShippingLabels() {}
}
