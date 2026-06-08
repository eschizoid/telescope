package io.github.eschizoid.telescope.demo.spring.api;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.demo.spring.domain.Customer;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderEntity;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderRepository;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime-resolution flavour of the order CRUD surface.
 *
 * <ul>
 *   <li>{@code POST /orders/runtime} — Jackson hydrates the JSON into the {@link Order} record;
 *       the runtime {@link Mapper} converts it into an {@link OrderEntity}; Hibernate persists it;
 *       the response runs {@code mapper.backward(...)} on the saved entity (which now has
 *       generated ids) and returns the JSON.
 *   <li>{@code GET /orders/runtime/{id}} — repository load, {@code mapper.backward}, return.
 *   <li>{@code PATCH /orders/runtime/{id}} — sparse-update demo. The request body is a partial
 *       {@link Order}; only the non-null fields land on the existing entity via
 *       {@code mapper.patch(existing, partial)}.
 * </ul>
 *
 * <p>The runtime path resolves accessor names via {@code SerializedLambda} decode + the codegen
 * holders' {@code constants()} fast path when present (transparent post-Phase-B). Both endpoints
 * are {@code @Transactional} so the repository ops + the telescope conversions happen inside one
 * unit of work.
 */
@RestController
@RequestMapping("/orders/runtime")
public class RuntimeOrderController {

  private final Mapper<Order, OrderEntity> orderMapper;
  private final OrderRepository orderRepository;

  public RuntimeOrderController(final Mapper<Order, OrderEntity> orderMapper, final OrderRepository orderRepository) {
    this.orderMapper = orderMapper;
    this.orderRepository = orderRepository;
  }

  @PostMapping
  @Transactional
  public ResponseEntity<Order> create(@RequestBody final Order request) {
    // Pre-write normalisation via telescope's deep-update path — lowercase the customer email
    // before persistence. Demonstrates a one-line cross-record field edit producing a new
    // top-level record (immutable update through 2 levels of nesting).
    final var normalised = Telescope.of(Order.class)
      .field(Order::customer)
      .field(Customer::email)
      .update(request, email -> email == null ? null : email.toLowerCase());

    final var entity = orderMapper.forward(normalised);
    final var saved = orderRepository.save(entity);
    return ResponseEntity.ok(orderMapper.backward(saved));
  }

  @GetMapping("/{id}")
  @Transactional(readOnly = true)
  public ResponseEntity<Order> get(@PathVariable final Long id) {
    return orderRepository.findById(id).map(orderMapper::backward).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PatchMapping("/{id}")
  @Transactional
  public ResponseEntity<Order> patch(@PathVariable final Long id, @RequestBody final Order partial) {
    final Optional<OrderEntity> existing = orderRepository.findById(id);
    if (existing.isEmpty()) return ResponseEntity.notFound().build();

    // Reconstruct: load the existing record state from the entity, sparse-overlay the non-null
    // fields from `partial`, then write the result back through the entity mapper. Demonstrates
    // mapper.patch(base: A, partial: B) — the sparse-update terminal that the deep factory builds
    // at top-level. `patch` reads each non-null component of the *target* type (B = OrderEntity)
    // and writes the corresponding *source* component (A = Order) — so we convert the partial
    // record into a partial entity first.
    final var existingRecord = orderMapper.backward(existing.get());
    final var partialEntity = orderMapper.forward(partial);
    final var patched = orderMapper.patch(existingRecord, partialEntity);
    final var saved = orderRepository.save(orderMapper.forward(patched));
    return ResponseEntity.ok(orderMapper.backward(saved));
  }
}
