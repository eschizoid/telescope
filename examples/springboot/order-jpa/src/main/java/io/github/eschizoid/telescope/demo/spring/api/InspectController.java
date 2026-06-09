package io.github.eschizoid.telescope.demo.spring.api;

import io.github.eschizoid.telescope.conversion.Mapper;
import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.demo.spring.domain.Order;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderEntity;
import io.github.eschizoid.telescope.demo.spring.persistence.OrderRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-style "inspect" endpoint that demonstrates {@link Telescope#fieldByName(String)} — the
 * documented runtime escape hatch for config-driven paths. The request body carries a dotted field
 * path (e.g. {@code "customer.email"}); the controller splits it on dots and chains a {@code
 * fieldByName} hop per segment, then reads the value out of the loaded order.
 *
 * <p>This is intentionally a runtime-typed path: the segments come from arbitrary JSON. Wrong field
 * names surface as an {@link IllegalArgumentException} at use site (mapped to {@code 400 Bad
 * Request} by the exception handler below).
 */
@RestController
@RequestMapping("/orders")
public class InspectController {

  private final Mapper<Order, OrderEntity> orderMapper;
  private final OrderRepository orderRepository;

  public InspectController(final Mapper<Order, OrderEntity> orderMapper, final OrderRepository orderRepository) {
    this.orderMapper = orderMapper;
    this.orderRepository = orderRepository;
  }

  @PostMapping("/{id}/inspect")
  @Transactional(readOnly = true)
  public ResponseEntity<InspectResponse> inspect(@PathVariable final Long id, @RequestBody final InspectRequest body) {
    final var maybeEntity = orderRepository.findById(id);
    if (maybeEntity.isEmpty()) return ResponseEntity.notFound().build();
    final var order = orderMapper.backward(maybeEntity.get());

    // Chain one fieldByName per dotted segment, starting from Telescope.of(Order.class). Each hop
    // widens the lens's leaf type to Object — that's the runtime price the .fieldByName escape
    // hatch pays. The final .read(order) lands the leaf value.
    Telescope<Order, Object> path = Telescope.of(Order.class).fieldByName(body.path().split("\\.")[0]);
    final var segments = body.path().split("\\.");
    for (var i = 1; i < segments.length; i++) {
      path = path.fieldByName(segments[i]);
    }
    final var value = path.read(order);
    return ResponseEntity.ok(new InspectResponse(body.path(), value == null ? null : value.toString()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<InspectError> handleBadPath(final IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(new InspectError(e.getMessage()));
  }

  public record InspectRequest(String path) {}

  public record InspectResponse(String path, String value) {}

  public record InspectError(String message) {}
}
