package io.github.eschizoid.telescope.quarkus.rest.runtime;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.GenericEntity;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveContainerRequestContext;

/**
 * Maps the response body of a method annotated with {@code TelescopeResponse}. The build binds this
 * filter to those methods only. It runs once Quarkus REST has turned the return value, a {@code
 * RestResponse} or the value a {@code Uni} completed with into a response, and before the body is
 * written: the entity is replaced with a {@link GenericEntity} of the mapped value and its type, so
 * the writer is chosen for the mapped type, and status and headers stay as they were. A response
 * produced from an exception, by an exception mapper or by Quarkus REST itself, is written as it
 * is, whatever the shape of its body.
 */
public class TelescopeResponseFilter implements ContainerResponseFilter {

  private final TelescopeRestEndpoints endpoints;

  /**
   * @param endpoints the mappings resolved at startup
   */
  @Inject
  public TelescopeResponseFilter(final TelescopeRestEndpoints endpoints) {
    this.endpoints = endpoints;
  }

  @Override
  public void filter(final ContainerRequestContext request, final ContainerResponseContext response) {
    final var entity = response.getEntity();
    if (entity == null) return;
    final var server = ((ResteasyReactiveContainerRequestContext) request).getServerRequestContext();
    if (server instanceof ResteasyReactiveRequestContext context && context.getThrowable() != null) return;
    final var info = server.getResteasyReactiveResourceInfo();
    if (info == null) return;
    final var mapping = endpoints.response(info.getMethodId());
    if (mapping == null || !mapping.accepts(entity)) return;
    response.setEntity(new GenericEntity<>(mapping.apply(entity), mapping.mappedType()));
  }
}
