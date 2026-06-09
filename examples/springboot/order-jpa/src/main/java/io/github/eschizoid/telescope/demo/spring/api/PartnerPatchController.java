package io.github.eschizoid.telescope.demo.spring.api;

import io.github.eschizoid.telescope.Mapper;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.partner.PartnerShippingLabel;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderEntity;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bug-hunt slice: cross-paradigm {@code mapper.patch(...)} — sparse overlay where the source is the
 * domain record {@code Order} and the target is the Lombok-bean {@code PartnerShippingLabel}.
 *
 * <p>The forward direction (record → Lombok bean) is exercised by {@code PartnerLabelController}.
 * This slice exercises the reverse-shaped patch: take a partial {@code PartnerShippingLabel} from
 * an upstream system (only a couple of fields set, the rest null), and overlay those non-null bean
 * fields onto the stored domain {@code Order} via {@code partnerLabelMapper.patch(order, partial)}.
 *
 * <p>{@code Mapper.patch(base: A, partial: B)} reads each non-null target component via the target
 * {@link io.github.eschizoid.telescope.internal.Reflective}, runs it back through the per-component
 * {@code Iso}, and writes the result through the source reflective. With {@code A = Order} (record)
 * and {@code B = PartnerShippingLabel} (Lombok bean), the patch table is keyed on bean property
 * names ({@code trackingReference}, {@code customer}, {@code items}, ...) but writes through the
 * record canonical constructor on the source side. That's the dispatch corner the test pins.
 */
@RestController
public class PartnerPatchController {

  private final Mapper<Order, OrderEntity> orderMapper;
  private final Mapper<Order, PartnerShippingLabel> partnerLabelMapper;
  private final OrderRepository orderRepository;

  public PartnerPatchController(
    final Mapper<Order, OrderEntity> orderMapper,
    final Mapper<Order, PartnerShippingLabel> partnerLabelMapper,
    final OrderRepository orderRepository
  ) {
    this.orderMapper = orderMapper;
    this.partnerLabelMapper = partnerLabelMapper;
    this.orderRepository = orderRepository;
  }

  /**
   * PATCH /orders/{id}/from-partner — overlay non-null fields from a partial {@code
   * PartnerShippingLabel} onto the stored {@code Order} record, save the result back through the
   * entity mapper. Returns the resulting Order (after Hibernate save + backward conversion).
   */
  @PatchMapping("/orders/{id}/from-partner")
  @Transactional
  public ResponseEntity<Order> patchFromPartner(
    @PathVariable final Long id,
    @RequestBody final PartnerShippingLabel partial
  ) {
    final var existing = orderRepository.findById(id);
    if (existing.isEmpty()) return ResponseEntity.notFound().build();

    final var existingRecord = orderMapper.backward(existing.get());
    // Cross-paradigm patch: source = Order record, target = PartnerShippingLabel Lombok bean.
    // The patch table inside partnerLabelMapper was built at top level only; it reads non-null
    // bean properties from `partial` and writes through Order's canonical constructor.
    final var patched = partnerLabelMapper.patch(existingRecord, partial);
    final var saved = orderRepository.save(orderMapper.forward(patched));
    return ResponseEntity.ok(orderMapper.backward(saved));
  }
}
