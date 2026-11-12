package io.github.eschizoid.telescope.demo.order;

import io.github.eschizoid.telescope.Sources;
import io.github.eschizoid.telescope.demo.api.InvoiceDto;
import io.github.eschizoid.telescope.demo.api.NewLine;
import io.github.eschizoid.telescope.demo.api.OrderChanges;
import io.github.eschizoid.telescope.demo.api.PlaceOrder;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * The order service's business rules, with no framework in them. Each app calls these from its own
 * endpoints, and the mapping in between is all in {@link OrderMappers} and {@code RequestMappers}.
 */
public final class OrderDesk {

  private final Orders orders;
  private final Seller seller;
  private final Clock clock;

  public OrderDesk(final Orders orders, final Seller seller, final Clock clock) {
    this.orders = orders;
    this.seller = seller;
    this.clock = clock;
  }

  public List<Order> list() {
    return orders.all();
  }

  public Order get(final String number) {
    return orders.byNumber(number).orElseThrow(() -> new NoSuchOrderException(number));
  }

  /**
   * Places an order. The mapper builds the entity and prices its lines from the catalog; the
   * service adds what only it knows: the number, the customer, the status and the date.
   */
  public Order place(final PlaceOrder command) {
    final var customer = orders
      .customerByEmail(command.customerEmail())
      .orElseThrow(() -> new IllegalArgumentException("no customer with email " + command.customerEmail()));
    checkLines(command.lines());
    checkDiscountCode(command.discountCode());
    final var order = OrderMappers.PLACE.forward(command);
    order.setNumber("SO-" + (1001 + orders.all().size()));
    order.setCustomer(customer);
    order.setStatus(OrderStatus.PLACED);
    order.setPlacedOn(LocalDate.now(clock));
    if (order.getDelivery() == null) {
      order.setDelivery(Delivery.STANDARD);
    }
    return orders.save(order);
  }

  /**
   * Applies a PATCH. The client's changes are laid over what the order holds now, and the result is
   * written into the entity in place, so the persistence context keeps tracking it and the list of
   * lines it manages is refilled rather than replaced.
   */
  public Order change(final String number, final OrderChanges changes) {
    final var order = get(number);
    require(order, OrderStatus.PLACED, "change");
    checkDiscountCode(changes.discountCode());
    if (changes.lines() != null) {
      checkLines(changes.lines());
    }
    OrderMappers.EDITS.patchInto(order, changes);
    return orders.save(order);
  }

  /**
   * Takes payment for a placed order. A paid order is fixed: its lines and options no longer
   * change. The warehouse prints the carrier's label as soon as an order is paid, which reserves
   * the tracking number; the public view shows it only once the parcel ships.
   */
  public Order pay(final String number) {
    final var order = get(number);
    require(order, OrderStatus.PLACED, "be paid");
    order.setStatus(OrderStatus.PAID);
    order.setTrackingNumber("TRK-" + order.getNumber().substring("SO-".length()));
    return orders.save(order);
  }

  /** Hands a paid order to the carrier. An order that has not been paid does not ship. */
  public Order ship(final String number) {
    final var order = get(number);
    require(order, OrderStatus.PAID, "ship");
    order.setStatus(OrderStatus.SHIPPED);
    return orders.save(order);
  }

  /** Cancels an order that has not shipped yet. A parcel on its way is a return, not a cancel. */
  public Order cancel(final String number) {
    final var order = get(number);
    if (order.getStatus() == OrderStatus.SHIPPED || order.getStatus() == OrderStatus.CANCELLED) {
      throw new IllegalStateException("order " + number + " is " + order.getStatus() + " and cannot be cancelled");
    }
    order.setStatus(OrderStatus.CANCELLED);
    return orders.save(order);
  }

  /** The order's invoice, merged from the order, its customer, the seller and the totals. */
  public InvoiceDto invoice(final String number) {
    final var order = get(number);
    return OrderMappers.INVOICE.forward(Sources.of(order, order.getCustomer(), seller, Pricing.totals(order)));
  }

  private static void require(final Order order, final OrderStatus status, final String action) {
    if (order.getStatus() != status) {
      throw new IllegalStateException(
        "order " + order.getNumber() + " is " + order.getStatus() + ", and only a " + status + " order can " + action
      );
    }
  }

  private static void checkLines(final List<NewLine> lines) {
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("an order needs at least one line");
    }
    for (final var line : lines) {
      if (line.quantity() < 1) {
        throw new IllegalArgumentException("line " + line.sku() + " has a quantity of " + line.quantity());
      }
    }
  }

  private static void checkDiscountCode(final String code) {
    if (code != null && !Pricing.knownCode(code)) {
      throw new IllegalArgumentException("unknown discount code " + code);
    }
  }
}
