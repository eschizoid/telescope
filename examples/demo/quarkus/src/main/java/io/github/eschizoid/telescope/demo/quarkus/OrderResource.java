package io.github.eschizoid.telescope.demo.quarkus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.eschizoid.telescope.demo.api.ImportTrace;
import io.github.eschizoid.telescope.demo.api.ImportedLine;
import io.github.eschizoid.telescope.demo.api.ImportedOrder;
import io.github.eschizoid.telescope.demo.api.InvoiceDto;
import io.github.eschizoid.telescope.demo.api.OrderChanges;
import io.github.eschizoid.telescope.demo.api.OrderDto;
import io.github.eschizoid.telescope.demo.api.OrderSummary;
import io.github.eschizoid.telescope.demo.api.PartnerFeed;
import io.github.eschizoid.telescope.demo.api.PlaceOrder;
import io.github.eschizoid.telescope.demo.order.Order;
import io.github.eschizoid.telescope.demo.order.OrderDesk;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.jboss.resteasy.reactive.RestResponse;
import shop.warehouse.ShippingLabel;
import shop.warehouse.ShippingLabels;

/**
 * The order API, version 1. Most methods return the entity the desk gave them, and {@link
 * TelescopeResponse} names the DTO the client receives: the starter maps the value where Quarkus
 * REST writes the body. {@code GET /orders/{number}} converts otherwise, through the entity's
 * generated {@code toOrderDto()}, and the partner import calls the {@link OrderImports} bean before
 * the desk places the order.
 *
 * <p>A refused import's trace replays the payload through a runtime {@code fromMap} mapper, which
 * reads {@link ImportedOrder} and {@link ImportedLine} by reflection; a native image holds that
 * reflection only for the records registered here.
 */
@RegisterForReflection(targets = { ImportedOrder.class, ImportedLine.class })
@Path("/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {

  @Inject
  OrderDesk desk;

  @Inject
  OrderImports imports;

  @Inject
  ObjectMapper json;

  /** The order list, one {@link OrderSummary} per order. */
  @GET
  @TelescopeResponse(to = OrderSummary.class)
  public List<Order> list() {
    return desk.list();
  }

  /** One order. The entity converts itself through the generated {@code OrderMappings}. */
  @GET
  @Path("/{number}")
  public OrderDto get(@PathParam("number") final String number) {
    return desk.get(number).toOrderDto();
  }

  @POST
  @TelescopeResponse(to = OrderDto.class)
  public RestResponse<Order> place(final PlaceOrder command) {
    return created(desk.place(command));
  }

  /** Changes only the fields the body sends. */
  @PATCH
  @Path("/{number}")
  @TelescopeResponse(to = OrderDto.class)
  public Order change(@PathParam("number") final String number, final OrderChanges changes) {
    return desk.change(number, changes);
  }

  @POST
  @Path("/{number}/pay")
  @Consumes(MediaType.WILDCARD)
  @TelescopeResponse(to = OrderDto.class)
  public Order pay(@PathParam("number") final String number) {
    return desk.pay(number);
  }

  @POST
  @Path("/{number}/ship")
  @Consumes(MediaType.WILDCARD)
  @TelescopeResponse(to = OrderDto.class)
  public Order ship(@PathParam("number") final String number) {
    return desk.ship(number);
  }

  @POST
  @Path("/{number}/cancel")
  @Consumes(MediaType.WILDCARD)
  @TelescopeResponse(to = OrderDto.class)
  public Order cancel(@PathParam("number") final String number) {
    return desk.cancel(number);
  }

  /**
   * Places an order from a partner's JSON, read as text by {@link PartnerFeed}. A payload the
   * service refuses is answered with a 400 that carries the trace of how each field was read. Only
   * a refused payload is parsed a second time, into the map the trace replays.
   */
  @POST
  @Path("/import")
  @TelescopeResponse(to = OrderDto.class)
  public RestResponse<Order> importOrder(final String body) {
    final Order order;
    try {
      order = desk.place(imports.map(PartnerFeed.read(body)));
    } catch (final IllegalArgumentException refused) {
      throw new ImportRefusedException(refused, ImportTrace.of(payload(body)));
    }
    return created(order);
  }

  private Map<String, Object> payload(final String body) {
    try {
      return json.readValue(body, new TypeReference<>() {});
    } catch (final JsonProcessingException notJson) {
      return Map.of();
    }
  }

  @GET
  @Path("/{number}/invoice")
  public InvoiceDto invoice(@PathParam("number") final String number) {
    return desk.invoice(number);
  }

  /**
   * The label the warehouse prints for an order, mapped by the warehouse's own {@link
   * ShippingLabels}.
   */
  @GET
  @Path("/{number}/label")
  @TelescopeResponse(to = ShippingLabel.class)
  public Order label(@PathParam("number") final String number) {
    return desk.get(number);
  }

  private static RestResponse<Order> created(final Order order) {
    return RestResponse.ResponseBuilder.<Order>created(URI.create("/orders/" + order.getNumber()))
      .entity(order)
      .build();
  }
}
