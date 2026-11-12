package io.github.eschizoid.telescope.demo.quarkus;

import io.github.eschizoid.telescope.demo.order.NoSuchOrderException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Turns the desk's refusals into error responses. */
public class ApiErrors {

  @ServerExceptionMapper
  public Response notFound(final NoSuchOrderException refused) {
    return error(Response.Status.NOT_FOUND, refused);
  }

  @ServerExceptionMapper
  public Response badRequest(final IllegalArgumentException refused) {
    return error(Response.Status.BAD_REQUEST, refused);
  }

  @ServerExceptionMapper
  public Response conflict(final IllegalStateException refused) {
    return error(Response.Status.CONFLICT, refused);
  }

  private static Response error(final Response.Status status, final RuntimeException refused) {
    return Response.status(status).type(MediaType.TEXT_PLAIN).entity(refused.getMessage()).build();
  }
}
