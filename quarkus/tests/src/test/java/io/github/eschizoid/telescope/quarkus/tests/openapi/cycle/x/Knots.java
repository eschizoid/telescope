package io.github.eschizoid.telescope.quarkus.tests.openapi.cycle.x;

import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * A mapped endpoint writing {@link Node}, beside an unmapped one returning it, so the document
 * already describes {@link Node} and {@link Link}, under whatever names it numbered them.
 */
@Path("/knots")
@Produces(MediaType.APPLICATION_JSON)
public class Knots {

  /**
   * @return a knot, written as {@link Node}
   */
  @GET
  @Path("/mapped")
  @TelescopeResponse(to = Node.class)
  public Knot mapped() {
    return new Knot("x", null);
  }

  /**
   * @return a node, unmapped
   */
  @GET
  @Path("/plain")
  public Node plain() {
    return new Node("x", null);
  }
}
