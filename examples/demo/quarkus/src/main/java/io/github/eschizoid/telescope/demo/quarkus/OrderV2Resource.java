package io.github.eschizoid.telescope.demo.quarkus;

import io.github.eschizoid.telescope.demo.api.OrderV2Dto;
import io.github.eschizoid.telescope.demo.api.PlaceOrder;
import io.github.eschizoid.telescope.demo.api.PlaceOrderV2;
import io.github.eschizoid.telescope.demo.order.Order;
import io.github.eschizoid.telescope.demo.order.OrderDesk;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeRequestBody;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.net.URI;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * The order API, version 2: the mobile app's request and response shapes over the same service. The
 * body is read as {@link PlaceOrderV2} and mapped to the {@link PlaceOrder} the desk takes before
 * {@link #place} runs, and every response is mapped from the entity to {@link OrderV2Dto}.
 */
@Path("/v2/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderV2Resource {

  @Inject
  OrderDesk desk;

  @GET
  @Path("/{number}")
  @TelescopeResponse(to = OrderV2Dto.class)
  public Order get(@PathParam("number") final String number) {
    return desk.get(number);
  }

  @POST
  @TelescopeResponse(to = OrderV2Dto.class)
  public RestResponse<Order> place(@TelescopeRequestBody(from = PlaceOrderV2.class) final PlaceOrder command) {
    final var order = desk.place(command);
    return RestResponse.ResponseBuilder.<Order>created(URI.create("/v2/orders/" + order.getNumber()))
      .entity(order)
      .build();
  }
}
