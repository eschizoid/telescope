package io.github.eschizoid.telescope.demo.spring.api;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
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
 * Admin-style "touch" endpoint that demonstrates {@link Telescope#fieldByName(String)} as a runtime
 * <b>write</b> escape hatch — the dotted field path comes from the request body and is resolved by
 * chained {@code fieldByName} calls, terminating in a {@code .set(...)} that rewrites the leaf and
 * persists the whole order.
 *
 * <p>Companion to {@link InspectController}, which exercises the read side of the same escape
 * hatch. Together they pin the runtime-checked surface: wrong field names surface as {@link
 * IllegalArgumentException} carrying the typo, the declaring class, and the list of known
 * components — exactly the diagnostic a config-driven caller needs when there is no compile-time
 * check to fall back on.
 */
@RestController
@RequestMapping("/orders")
public class AdminTouchController {

  private final Mapper<Order, OrderEntity> orderMapper;
  private final OrderRepository orderRepository;

  public AdminTouchController(final Mapper<Order, OrderEntity> orderMapper, final OrderRepository orderRepository) {
    this.orderMapper = orderMapper;
    this.orderRepository = orderRepository;
  }

  @PostMapping("/{id}/admin-touch")
  @Transactional
  public ResponseEntity<Order> touch(@PathVariable final Long id, @RequestBody final AdminTouchRequest body) {
    final var maybeEntity = orderRepository.findById(id);
    if (maybeEntity.isEmpty()) return ResponseEntity.notFound().build();
    final var order = orderMapper.backward(maybeEntity.get());

    // Chain one fieldByName per dotted segment. The leaf type widens to Object — that's the
    // runtime price the .fieldByName escape hatch pays in lieu of a compile-time guarantee. The
    // final .set(...) rewrites the leaf and rebuilds the whole record back up to Order.
    final var segments = body.field().split("\\.");
    Telescope<Order, Object> path = Telescope.of(Order.class).fieldByName(segments[0]);
    for (var i = 1; i < segments.length; i++) {
      path = path.fieldByName(segments[i]);
    }
    final var touched = path.set(order, body.value());

    final var entity = orderMapper.forward(touched);
    final var saved = orderRepository.save(entity);
    return ResponseEntity.ok(orderMapper.backward(saved));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<AdminTouchError> handleBadPath(final IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(new AdminTouchError(e.getMessage()));
  }

  public record AdminTouchRequest(String field, Object value) {}

  public record AdminTouchError(String message) {}
}
