package io.github.eschizoid.telescope.demo.spring.mapping;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.domain.RedactedOrder;

/**
 * Hand-rolled {@code Telescope<Order, RedactedOrder>} built through {@link
 * Telescope#from(Class)}{@code .to(...).using(forward, backward)}. The conversion is
 * <b>unidirectional / lossy</b> by design: {@code forward} obfuscates customer email and shipping
 * city; {@code backward} cannot recover the originals and therefore throws.
 *
 * <p>This is the documented "honest escape hatch" for a non-bijective {@code Iso}: tell the lattice
 * the truth (one-way) rather than fabricate a synthetic round-trip that satisfies neither {@code
 * get-then-set} nor {@code set-then-get}.
 */
public final class RedactedOrderTelescopes {

  private RedactedOrderTelescopes() {}

  /**
   * Marker carried by the backward {@link UnsupportedOperationException} so callers can
   * pattern-match.
   */
  public static final String BACKWARD_MESSAGE = "RedactedOrder is a lossy projection of Order; cannot invert.";

  /**
   * Forward redacts {@code customer.email} (keep the domain after {@code @}, replace the local part
   * with {@code "xxx"}) and {@code shippingAddress.city} (first letter + {@code "***"}). All other
   * order fields drop on the floor — line items, billing, gift wrap, customer name and id.
   *
   * <p>Backward throws — see {@link #BACKWARD_MESSAGE}. Anything that touches a write path on this
   * telescope ({@code update}, {@code .then(...)} -> write) gets the same exception.
   */
  public static final Telescope<Order, RedactedOrder> REDACT = Telescope.from(Order.class)
    .to(RedactedOrder.class)
    .using(RedactedOrderTelescopes::redact, redacted -> {
      throw new UnsupportedOperationException(BACKWARD_MESSAGE);
    });

  static RedactedOrder redact(final Order order) {
    if (order == null) return null;
    final var customer = order.customer();
    final var shipping = order.shippingAddress();
    return new RedactedOrder(
      order.id(),
      order.orderNumber(),
      customer == null ? null : redactEmail(customer.email()),
      shipping == null ? null : redactCity(shipping.city())
    );
  }

  static String redactEmail(final String email) {
    if (email == null || email.isEmpty()) return email;
    final var at = email.indexOf('@');
    if (at < 0) return "xxx";
    return "xxx" + email.substring(at);
  }

  static String redactCity(final String city) {
    if (city == null || city.isEmpty()) return city;
    return city.substring(0, 1) + "***";
  }
}
