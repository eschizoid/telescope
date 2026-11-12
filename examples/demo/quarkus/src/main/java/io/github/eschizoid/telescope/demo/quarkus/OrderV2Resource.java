package io.github.eschizoid.telescope.demo.quarkus;

import io.github.eschizoid.telescope.demo.api.OrderV2Dto;
import io.github.eschizoid.telescope.demo.api.PlaceOrderV2;
import io.github.eschizoid.telescope.demo.api.RequestMappers;
import io.github.eschizoid.telescope.demo.order.OrderDesk;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;

/** The order API, version 2: its own request and response shapes over the same service. */
@Path("/v2/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderV2Resource {

  @Inject
  OrderDesk desk;

  @GET
  @Path("/{number}")
  public OrderV2Dto get(@PathParam("number") final String number) {
    return desk.get(number).toOrderV2Dto();
  }

  @POST
  public Response place(final PlaceOrderV2 request) {
    final var order = desk.place(RequestMappers.V2.forward(request));
    return Response.created(URI.create("/v2/orders/" + order.getNumber())).entity(order.toOrderV2Dto()).build();
  }
}
