package io.github.eschizoid.telescope.demo.spring.api;

import static io.github.eschizoid.telescope.Edit.mapIfPresent;
import static io.github.eschizoid.telescope.Edit.overIfPresent;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.demo.spring.domain.Address;
import io.github.eschizoid.telescope.demo.spring.domain.Customer;
import io.github.eschizoid.telescope.demo.spring.domain.LineItem;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderEntity;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sparse-PATCH endpoint built on {@link Telescope#all(io.github.eschizoid.telescope.Edit[])} with
 * the {@code overIfPresent(...)} ergonomic shape. Each non-null field of {@link BulkUpdateRequest}
 * contributes one slot to the bundle; null fields short-circuit to identity. No conditional
 * builder, no ArrayList — the call site reads as the contract.
 */
@RestController
public class BulkUpdateController {

  private static final Telescope<Order, String> ORDER_NUMBER = Telescope.of(Order.class).field(Order::orderNumber);

  private static final Telescope<Order, String> CUSTOMER_EMAIL = Telescope.of(Order.class)
    .field(Order::customer)
    .field(Customer::email);

  private static final Telescope<Order, String> SHIPPING_CITY = Telescope.of(Order.class)
    .field(Order::shippingAddress)
    .field(Address::city);

  private static final Telescope<Order, String> BILLING_CITY = Telescope.of(Order.class)
    .field(Order::billingAddress)
    .field(Address::city);

  private static final Telescope<Order, Integer> LINE_ITEM_QUANTITIES = Telescope.of(Order.class)
    .each(Order::lineItems)
    .field(LineItem::quantity);

  private final Mapper<Order, OrderEntity> orderMapper;
  private final OrderRepository orderRepository;

  public BulkUpdateController(final Mapper<Order, OrderEntity> orderMapper, final OrderRepository orderRepository) {
    this.orderMapper = orderMapper;
    this.orderRepository = orderRepository;
  }

  @PostMapping("/orders/{id}/bulk-update")
  @Transactional
  public ResponseEntity<Order> bulkUpdate(@PathVariable final Long id, @RequestBody final BulkUpdateRequest req) {
    final var entity = orderRepository.findById(id).orElse(null);
    if (entity == null) return ResponseEntity.notFound().build();
    final var current = orderMapper.backward(entity);

    final Telescope<Order, Order> patch = Telescope.all(
      overIfPresent(ORDER_NUMBER, req.orderNumber()),
      overIfPresent(CUSTOMER_EMAIL, req.customerEmail(), String::toLowerCase),
      overIfPresent(SHIPPING_CITY, req.shippingCity()),
      overIfPresent(BILLING_CITY, req.billingCity()),
      mapIfPresent(LINE_ITEM_QUANTITIES, req.lineItemQuantityDelta(), Integer::sum)
    );
    final var updated = patch.apply(current);

    final var saved = orderRepository.save(orderMapper.forward(updated));
    return ResponseEntity.ok(orderMapper.backward(saved));
  }
}
