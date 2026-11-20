package io.github.eschizoid.telescope.demo.quarkus;

import io.github.eschizoid.telescope.demo.order.NoSuchOrderException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import tools.jackson.core.JacksonException;

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

  /** A partner's order the desk refused, with the trace of how its payload was read. */
  @ServerExceptionMapper
  public Response importRefused(final ImportRefusedException refused) {
    return problem(Map.of("detail", refused.getMessage(), "trace", refused.trace()));
  }

  /** A body that is not JSON at all, which the partner feed's binder hands back as it parses. */
  @ServerExceptionMapper
  public Response notJson(final JacksonException refused) {
    return problem(Map.of("detail", refused.getOriginalMessage()));
  }

  private static Response error(final Response.Status status, final RuntimeException refused) {
    return Response.status(status).type(MediaType.TEXT_PLAIN).entity(refused.getMessage()).build();
  }

  private static Response problem(final Map<String, String> problem) {
    return Response.status(Response.Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON).entity(problem).build();
  }
}
