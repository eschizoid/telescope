package io.github.eschizoid.telescope.demo.spring.api;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.demo.spring.domain.LineItem;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.domain.OrderPath;
import io.github.eschizoid.telescope.demo.spring.persistence.LineItemEntity;
import io.github.eschizoid.telescope.demo.spring.persistence.LineItemEntityPath;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderEntity;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Codegen-driven flavour of the order CRUD surface. Identical API contract and identical
 * persistence behaviour to {@link RuntimeOrderController}; the {@code Mapper<Order, OrderEntity>}
 * bean is shared. What differs is how this controller does <b>deep navigation</b>: it consumes the
 * typed {@code OrderPath<R>} navigator emitted by the {@code FocusProcessor} from the {@link
 * io.github.eschizoid.telescope.annotations.Focus @Focus} annotations on the records.
 *
 * <p>The runtime controller's pre-write email normalisation is:
 *
 * <pre>{@code
 * Telescope.of(Order.class)
 *     .field(Order::customer)
 *     .field(Customer::email)
 *     .update(request, normalise);
 * }</pre>
 *
 * <p>This controller's equivalent:
 *
 * <pre>{@code
 * OrderPath.start()
 *     .customer()
 *     .email()
 *     .update(request, normalise);
 * }</pre>
 *
 * <p>The first form decodes {@code Order::customer} / {@code Customer::email} method-references via
 * {@code SerializedLambda} on first call (then HashMap-cached). The second form is fully
 * compile-time-bound — no {@code SerializedLambda} decode anywhere, no per-call probe. Both produce
 * a {@code Telescope<Order, String>} that updates {@code customer.email} on an immutable {@code
 * Order} record. Same behaviour, different ergonomic and performance contract.
 *
 * <p>{@code OrderPath}, {@code OrderTelescope}, and the {@code <X>Bridge} constants that back
 * {@code @Bridge(EntityType.class)} on the leaf records are all <b>generated at compile time</b> by
 * the telescope annotation processors registered as {@code
 * annotationProcessor("io.github.eschizoid:telescope-codegen:0.4.0")} in {@code build.gradle.kts}.
 * Look in {@code build/generated/sources/annotationProcessor/...} after a build to see them.
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code POST /orders/codegen} — create with pre-write deep-update via {@link OrderPath}.
 *   <li>{@code GET /orders/codegen/{id}} — load and return.
 *   <li>{@code POST /orders/codegen/normalise-emails/{id}} — re-normalise an existing order's
 *       customer email through the typed navigator (server-side mutation through codegen).
 * </ul>
 */
@RestController
@RequestMapping("/orders/codegen")
public class CodegenOrderController {

  private final Mapper<Order, OrderEntity> orderMapper;
  private final Mapper<LineItem, LineItemEntity> lineItemMapper;
  private final OrderRepository orderRepository;

  public CodegenOrderController(
    final Mapper<Order, OrderEntity> orderMapper,
    final Mapper<LineItem, LineItemEntity> lineItemMapper,
    final OrderRepository orderRepository
  ) {
    this.orderMapper = orderMapper;
    this.lineItemMapper = lineItemMapper;
    this.orderRepository = orderRepository;
  }

  @PostMapping
  @Transactional
  public ResponseEntity<Order> create(@RequestBody final Order request) {
    // Codegen path: the typed OrderPath navigator descends customer.email at compile time.
    // The processor generated `OrderPath#customer()` returning a CustomerPath<Order>, whose
    // `email()` method returns a Telescope<Order, String> — fully compile-checked, no runtime
    // decode, no SerializedLambda crackopen, no probe miss.
    final var normalised = OrderPath.start()
      .customer()
      .email()
      .update(request, email -> email == null ? null : email.toLowerCase());

    final var saved = orderRepository.save(orderMapper.forward(normalised));
    return ResponseEntity.ok(orderMapper.backward(saved));
  }

  @GetMapping("/{id}")
  @Transactional(readOnly = true)
  public ResponseEntity<Order> get(@PathVariable final Long id) {
    return orderRepository
      .findById(id)
      .map(orderMapper::backward)
      .map(ResponseEntity::ok)
      .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping("/{id}/discount")
  @Transactional
  public ResponseEntity<Order> applyDiscount(
    @PathVariable final Long id,
    @RequestParam(defaultValue = "10") final int percent
  ) {
    // Demonstrates Mapper.asTelescope() composing across paradigms in one typed pipeline:
    //   1. OrderPath.start().lineItems().each().get() — typed record-side traversal down to a
    //      Telescope<Order, LineItem>. Codegen-emitted, compile-time-bound, multi-focus.
    //   2. .then(lineItemMapper.asTelescope()) — bridge into the entity side. The mapper
    //      exposes its bidirectional Iso<LineItem, LineItemEntity> as a Telescope so the lattice
    //      `.then(...)` can compose it. Result: Telescope<Order, LineItemEntity>.
    //   3. new LineItemEntityPath<>(...) — wrap the bridged Telescope back into a
    //      typed entity-side navigator. The Path ctor is public (intentional codegen surface, so
    //      cross-package bridge hops and mid-chain entries like this one can construct one).
    //      LineItemEntityPath's `unitPriceCents()` returns Telescope<Order, Long> — fully typed,
    //      no runtime SerializedLambda decode at the leaf.
    //   4. .update(record, cents -> ...) — fn runs on every line item's cents.
    //      Backward composition routes the new cents value through `lineItemMapper`'s reverse
    //      direction, materialising a LineItem with the new BigDecimal unitPrice. The result is
    //      a fresh Order with discounted prices.
    return orderRepository
      .findById(id)
      .map(orderMapper::backward)
      .map(record ->
        new LineItemEntityPath<>(OrderPath.start().lineItems().each().get().then(lineItemMapper.asTelescope()))
          .unitPriceCents()
          .update(record, cents -> (cents * (100L - percent)) / 100L)
      )
      .map(record -> orderRepository.save(orderMapper.forward(record)))
      .map(orderMapper::backward)
      .map(ResponseEntity::ok)
      .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping("/normalise-emails/{id}")
  @Transactional
  public ResponseEntity<Order> normaliseEmails(@PathVariable final Long id) {
    return orderRepository
      .findById(id)
      .map(orderMapper::backward)
      .map(record ->
        OrderPath.start()
          .customer()
          .email()
          .update(record, email -> email == null ? null : email.toLowerCase())
      )
      .map(record -> orderRepository.save(orderMapper.forward(record)))
      .map(orderMapper::backward)
      .map(ResponseEntity::ok)
      .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
