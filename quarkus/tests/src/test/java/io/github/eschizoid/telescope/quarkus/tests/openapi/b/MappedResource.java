package io.github.eschizoid.telescope.quarkus.tests.openapi.b;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.Order;
import io.github.eschizoid.telescope.quarkus.tests.fixtures.OrderDto;
import io.github.eschizoid.telescope.quarkus.tests.openapi.Consignment;
import io.github.eschizoid.telescope.quarkus.tests.openapi.Delivery;
import io.github.eschizoid.telescope.quarkus.tests.openapi.Packet;
import io.github.eschizoid.telescope.quarkus.tests.openapi.Parcel;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Mapped endpoints whose wire types share simple names with other classes, beside an unmapped one
 * that returns this package's {@link LineDto}, so both scans of the application see both classes
 * named {@code LineDto}.
 */
@Path("/mapped")
@Produces(MediaType.APPLICATION_JSON)
public class MappedResource {

  /**
   * @return an order, written as {@link OrderDto}
   */
  @GET
  @Path("/order")
  @TelescopeResponse(to = OrderDto.class)
  public Order order() {
    return new Order("SO-1", "Ada", 1200);
  }

  /**
   * @return a consignment, written as {@link Packet}
   */
  @GET
  @Path("/packet")
  @TelescopeResponse(to = Packet.class)
  public Consignment packet() {
    return new Consignment("C-1", new Consignment.Line("SKU-1", 2));
  }

  /**
   * @return a delivery, written as {@link Parcel}
   */
  @GET
  @Path("/parcel")
  @TelescopeResponse(to = Parcel.class)
  public Delivery parcel() {
    return new Delivery("D-1", new Delivery.Article("SKU-1", 2));
  }

  /**
   * @return a line, unmapped
   */
  @GET
  @Path("/line")
  public LineDto line() {
    return new LineDto(2);
  }
}
