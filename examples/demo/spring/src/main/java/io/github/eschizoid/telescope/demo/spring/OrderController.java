package io.github.eschizoid.telescope.demo.spring;

import io.github.eschizoid.telescope.demo.api.ImportTrace;
import io.github.eschizoid.telescope.demo.api.InvoiceDto;
import io.github.eschizoid.telescope.demo.api.OrderChanges;
import io.github.eschizoid.telescope.demo.api.OrderDto;
import io.github.eschizoid.telescope.demo.api.OrderRow;
import io.github.eschizoid.telescope.demo.api.OrderSummary;
import io.github.eschizoid.telescope.demo.api.OrderV2Dto;
import io.github.eschizoid.telescope.demo.api.PartnerFeed;
import io.github.eschizoid.telescope.demo.api.PlaceOrder;
import io.github.eschizoid.telescope.demo.api.PlaceOrderV2;
import io.github.eschizoid.telescope.demo.order.Order;
import io.github.eschizoid.telescope.demo.order.OrderStatus;
import io.github.eschizoid.telescope.spring.web.TelescopeGetMapping;
import io.github.eschizoid.telescope.spring.web.TelescopePatchMapping;
import io.github.eschizoid.telescope.spring.web.TelescopePostMapping;
import io.github.eschizoid.telescope.spring.web.TelescopeRequestBody;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The order API. Most handlers return the entity the service gave them, and the {@code Telescope*}
 * mapping annotations name the DTO the client receives: the starter maps the value where Spring MVC
 * writes the body. Three handlers convert otherwise: {@code GET /orders/{number}} calls the
 * entity's generated {@code toOrderDto()}, the dashboard gets its rows from the service, which
 * decides per request whether they carry tags, and the partner import calls the {@link
 * OrderImports} bean before the service places the order.
 */
@RestController
class OrderController {

  private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() {};

  private final OrderService orders;
  private final OrderImports imports;
  private final JsonMapper json;

  OrderController(final OrderService orders, final OrderImports imports, final JsonMapper json) {
    this.orders = orders;
    this.imports = imports;
    this.json = json;
  }

  /** The order list, one {@link OrderSummary} per order. */
  @TelescopeGetMapping(value = "/orders", to = OrderSummary.class)
  List<Order> list() {
    return orders.list();
  }

  /**
   * The back office's dashboard: one page of the orders in a status, newest first, one {@link
   * OrderRow} each. The status is named in any case, and a missing or unknown one is a 400 that
   * lists the statuses there are. A row carries its tags only when the client asks for them with
   * {@code expand=tags}, which loads them for the whole page in one more query.
   */
  @GetMapping("/orders/dashboard")
  Page<OrderRow> dashboard(
    @RequestParam(required = false) final String status,
    @PageableDefault(size = 20, sort = { "placedOn", "number" }, direction = Direction.DESC) final Pageable pageable,
    @RequestParam(defaultValue = "") final Set<String> expand
  ) {
    return orders.dashboard(OrderStatus.named(status), pageable, expand.contains("tags"));
  }

  /** One order. The entity converts itself through the generated {@code OrderMappings}. */
  @GetMapping("/orders/{number}")
  OrderDto get(@PathVariable final String number) {
    return orders.get(number).toOrderDto();
  }

  @TelescopePostMapping(value = "/orders", to = OrderDto.class)
  ResponseEntity<Order> place(@RequestBody final PlaceOrder command) {
    return created("/orders/", orders.place(command));
  }

  /** Changes only the fields the body sends, while the order is still unpaid. */
  @TelescopePatchMapping(value = "/orders/{number}", to = OrderDto.class)
  Order change(@PathVariable final String number, @RequestBody final OrderChanges changes) {
    return orders.change(number, changes);
  }

  @TelescopePostMapping(value = "/orders/{number}/pay", to = OrderDto.class)
  Order pay(@PathVariable final String number) {
    return orders.pay(number);
  }

  @TelescopePostMapping(value = "/orders/{number}/ship", to = OrderDto.class)
  Order ship(@PathVariable final String number) {
    return orders.ship(number);
  }

  @TelescopePostMapping(value = "/orders/{number}/cancel", to = OrderDto.class)
  Order cancel(@PathVariable final String number) {
    return orders.cancel(number);
  }

  /**
   * Places an order from a partner's JSON, read as text by {@link PartnerFeed}. A payload the
   * service refuses is answered with a 400 that carries the trace of how each field was read, which
   * is usually enough for the partner to see what they sent wrong. Only a refused payload is parsed
   * a second time, into the map the trace replays.
   */
  @TelescopePostMapping(value = "/orders/import", to = OrderDto.class)
  ResponseEntity<Order> importOrder(@RequestBody final String body) {
    final Order order;
    try {
      order = orders.place(imports.map(PartnerFeed.read(body)));
    } catch (final IllegalArgumentException refused) {
      throw new ImportRefusedException(refused, ImportTrace.of(json.readValue(body, PAYLOAD)));
    }
    return created("/orders/", order);
  }

  @GetMapping("/orders/{number}/invoice")
  InvoiceDto invoice(@PathVariable final String number) {
    return orders.invoice(number);
  }

  @TelescopeGetMapping(value = "/v2/orders/{number}", to = OrderV2Dto.class)
  Order getV2(@PathVariable final String number) {
    return orders.get(number);
  }

  /** The mobile app's request shape, mapped to {@link PlaceOrder} before this runs. */
  @TelescopePostMapping(value = "/v2/orders", to = OrderV2Dto.class)
  ResponseEntity<Order> placeV2(@TelescopeRequestBody(from = PlaceOrderV2.class) final PlaceOrder command) {
    return created("/v2/orders/", orders.place(command));
  }

  private static ResponseEntity<Order> created(final String prefix, final Order order) {
    return ResponseEntity.created(URI.create(prefix + order.getNumber())).body(order);
  }
}
