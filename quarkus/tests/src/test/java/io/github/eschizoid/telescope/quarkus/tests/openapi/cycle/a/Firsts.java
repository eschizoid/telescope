package io.github.eschizoid.telescope.quarkus.tests.openapi.cycle.a;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * An unmapped endpoint in a class whose name sorts before every other resource's, so SmallRye
 * OpenAPI, which reads resource classes in name order, gives this package's {@link Node} the name
 * {@code Node}.
 */
@Path("/firsts")
@Produces(MediaType.APPLICATION_JSON)
public class Firsts {

  /**
   * @return a node of this package
   */
  @GET
  public Node first() {
    return new Node(true);
  }
}
