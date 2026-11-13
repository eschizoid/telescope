package io.github.eschizoid.telescope.demo.quarkus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.eschizoid.telescope.demo.api.ImportTrace;
import io.github.eschizoid.telescope.demo.api.InvoiceDto;
import io.github.eschizoid.telescope.demo.api.OrderChanges;
import io.github.eschizoid.telescope.demo.api.OrderDto;
import io.github.eschizoid.telescope.demo.api.OrderSummary;
import io.github.eschizoid.telescope.demo.api.PartnerFeed;
import io.github.eschizoid.telescope.demo.api.PlaceOrder;
import io.github.eschizoid.telescope.demo.order.Order;
import io.github.eschizoid.telescope.demo.order.OrderDesk;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;

/**
 * The order API, version 1. Every response is the entity converting itself through the generated
 * {@code OrderMappings}: {@code order.toOrderDto()}, {@code order.toOrderSummary()}.
 */
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

  @GET
  public List<OrderSummary> list() {
    return desk.list().stream().map(Order::toOrderSummary).toList();
  }

  @GET
  @Path("/{number}")
  public OrderDto get(@PathParam("number") final String number) {
    return desk.get(number).toOrderDto();
  }

  @POST
  public Response place(final PlaceOrder command) {
    return created(desk.place(command));
  }

  /** Changes only the fields the body sends. */
  @PATCH
  @Path("/{number}")
  public OrderDto change(@PathParam("number") final String number, final OrderChanges changes) {
    return desk.change(number, changes).toOrderDto();
  }

  @POST
  @Path("/{number}/pay")
  @Consumes(MediaType.WILDCARD)
  public OrderDto pay(@PathParam("number") final String number) {
    return desk.pay(number).toOrderDto();
  }

  @POST
  @Path("/{number}/ship")
  @Consumes(MediaType.WILDCARD)
  public OrderDto ship(@PathParam("number") final String number) {
    return desk.ship(number).toOrderDto();
  }

  @POST
  @Path("/{number}/cancel")
  @Consumes(MediaType.WILDCARD)
  public OrderDto cancel(@PathParam("number") final String number) {
    return desk.cancel(number).toOrderDto();
  }

  /**
   * Places an order from a partner's JSON, read as text by {@link PartnerFeed}. A payload the
   * service refuses is answered with a 400 that carries the trace of how each field was read. Only
   * a refused payload is parsed a second time, into the map the trace replays.
   */
  @POST
  @Path("/import")
  public Response importOrder(final String body) {
    try {
      return created(desk.place(imports.map(PartnerFeed.read(body))));
    } catch (final IllegalArgumentException refused) {
      return badRequest(Map.of("detail", refused.getMessage(), "trace", ImportTrace.of(payload(body))));
    } catch (final JacksonException notJson) {
      return badRequest(Map.of("detail", notJson.getOriginalMessage()));
    }
  }

  private Map<String, Object> payload(final String body) {
    try {
      return json.readValue(body, new TypeReference<>() {});
    } catch (final JsonProcessingException notJson) {
      return Map.of();
    }
  }

  private static Response badRequest(final Map<String, String> problem) {
    return Response.status(Response.Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON).entity(problem).build();
  }

  @GET
  @Path("/{number}/invoice")
  public InvoiceDto invoice(@PathParam("number") final String number) {
    return desk.invoice(number);
  }

  private static Response created(final Order order) {
    return Response.created(URI.create("/orders/" + order.getNumber())).entity(order.toOrderDto()).build();
  }
}
