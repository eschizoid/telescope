package io.github.eschizoid.telescope.quarkus.tests.openapi.other;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * An unmapped endpoint in a class no mapped method sits in, so only the application's own document
 * sees this package's {@link OrderDto}.
 */
@Path("/elsewhere")
@Produces(MediaType.APPLICATION_JSON)
public class ElsewhereResource {

  /**
   * @return a value of this package's {@link OrderDto}
   */
  @GET
  public OrderDto other() {
    return new OrderDto("x", true);
  }

  /**
   * @return a value of this package's {@link Parcel}
   */
  @GET
  @Path("/parcel")
  public Parcel parcel() {
    return new Parcel("P-1", new Parcel.Item(2));
  }
}
