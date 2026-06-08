package io.github.eschizoid.telescope.demo.spring.api;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.partner.PartnerShippingLabel;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderEntity;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generates a partner SDK's shipping label from a stored {@code Order}. Showcases the three-way
 * coexistence of telescope, Lombok, and Jackson on the same Spring Boot stack:
 *
 * <ul>
 *   <li><b>Telescope's deep-mapping factory</b> handles {@code Order → PartnerShippingLabel} via
 *       the {@code Mapper<Order, PartnerShippingLabel>} bean wired in {@code OrderMappers}. Same
 *       primitives as the {@code Order → OrderEntity} mapping ({@code to(rename)}, {@code
 *       via(elementMapper)}, {@code writeBeans(SETTERS)}) — just with Lombok-emitted setters on the
 *       target side instead of Hibernate-aware ones.
 *   <li><b>Lombok's synthesised setters</b> are how telescope rebuilds the partner-side graph. The
 *       {@code SETTERS} write strategy walks {@code @Data}-emitted setters; the round-deferred
 *       {@code telescope-lombok} processor pass ensures the holder constants exist by the time the
 *       runtime probe looks for them.
 *   <li><b>Jackson's snake_case wire format</b> kicks in at HTTP marshal time only. Telescope reads
 *       {@code shipToAddress} (the Java property) when building the label; Jackson writes {@code
 *       ship_to_address} when serialising to JSON. The {@code @JsonProperty} annotations on {@link
 *       PartnerShippingLabel} carry no runtime cost during the telescope conversion.
 * </ul>
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code GET /orders/{id}/partner-label} — load the stored order, convert to the partner wire
 *       format, return as JSON with snake_case fields.
 * </ul>
 */
@RestController
@RequestMapping("/orders")
public class PartnerLabelController {

  private final Mapper<Order, OrderEntity> orderMapper;
  private final Mapper<Order, PartnerShippingLabel> partnerLabelMapper;
  private final OrderRepository orderRepository;

  public PartnerLabelController(
    final Mapper<Order, OrderEntity> orderMapper,
    final Mapper<Order, PartnerShippingLabel> partnerLabelMapper,
    final OrderRepository orderRepository
  ) {
    this.orderMapper = orderMapper;
    this.partnerLabelMapper = partnerLabelMapper;
    this.orderRepository = orderRepository;
  }

  @GetMapping("/{id}/partner-label")
  @Transactional(readOnly = true)
  public ResponseEntity<PartnerShippingLabel> generateLabel(@PathVariable final Long id) {
    return orderRepository
      .findById(id)
      .map(orderMapper::backward)
      .map(partnerLabelMapper::forward)
      .map(ResponseEntity::ok)
      .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
