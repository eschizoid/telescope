package io.github.eschizoid.telescope.demo.spring.bughunt.redacted;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderEntity;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only endpoint that returns a redacted projection of a stored {@code Order}. Loads via the
 * {@code Mapper<Order, OrderEntity>} bean, hands the loaded {@code Order} to the lossy {@link
 * RedactedOrderTelescopes#REDACT REDACT} telescope, and returns the {@link RedactedOrder} for
 * Jackson to serialise.
 */
@RestController
@RequestMapping("/orders")
public class RedactedOrderController {

  private final Mapper<Order, OrderEntity> orderMapper;
  private final OrderRepository orderRepository;

  public RedactedOrderController(final Mapper<Order, OrderEntity> orderMapper, final OrderRepository orderRepository) {
    this.orderMapper = orderMapper;
    this.orderRepository = orderRepository;
  }

  @GetMapping("/{id}/redacted")
  @Transactional(readOnly = true)
  public ResponseEntity<RedactedOrder> getRedacted(@PathVariable final Long id) {
    return orderRepository
      .findById(id)
      .map(orderMapper::backward)
      .map(RedactedOrderTelescopes.REDACT::read)
      .map(ResponseEntity::ok)
      .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
