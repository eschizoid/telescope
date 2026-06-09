package io.github.eschizoid.telescope.demo.spring.bughunt.bulkupdate;

import static io.github.eschizoid.telescope.Edit.over;

import io.github.eschizoid.telescope.Edit;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.demo.spring.domain.Address;
import io.github.eschizoid.telescope.demo.spring.domain.Customer;
import io.github.eschizoid.telescope.demo.spring.domain.LineItem;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderEntity;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exercises {@link Telescope#all(Edit[])} inside Spring's transactional boundary on a real domain.
 * Each non-null field of {@link BulkUpdateRequest} contributes one {@code over(PATH, fn)} edit; the
 * controller folds the lot into a single reusable {@code Telescope<Order, Order>} and applies it
 * once to the loaded record before persisting. The call shape keeps the count visible at a glance —
 * one {@code over(...)} per line, no chain blur.
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

    final List<Edit<Order>> edits = new ArrayList<>();
    if (req.orderNumber() != null) edits.add(over(ORDER_NUMBER, prev -> req.orderNumber()));
    if (req.customerEmail() != null) edits.add(over(CUSTOMER_EMAIL, prev -> req.customerEmail().toLowerCase()));
    if (req.shippingCity() != null) edits.add(over(SHIPPING_CITY, prev -> req.shippingCity()));
    if (req.billingCity() != null) edits.add(over(BILLING_CITY, prev -> req.billingCity()));
    if (req.lineItemQuantityDelta() != null) edits.add(
      over(LINE_ITEM_QUANTITIES, q -> q + req.lineItemQuantityDelta())
    );

    @SuppressWarnings({ "unchecked", "rawtypes" })
    final Telescope<Order, Order> bundle = Telescope.all(edits.toArray(new Edit[0]));
    final var updated = bundle.apply(current);

    final var saved = orderRepository.save(orderMapper.forward(updated));
    return ResponseEntity.ok(orderMapper.backward(saved));
  }
}
