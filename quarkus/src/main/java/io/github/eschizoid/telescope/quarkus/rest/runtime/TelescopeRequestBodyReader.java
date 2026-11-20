package io.github.eschizoid.telescope.quarkus.rest.runtime;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Providers;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveResourceInfo;
import org.jboss.resteasy.reactive.server.spi.ServerMessageBodyReader;
import org.jboss.resteasy.reactive.server.spi.ServerRequestContext;

/**
 * Reads the body parameter of a resource method the build found as the wire type, and hands the
 * method the mapped value. It claims only those parameters, so every other body goes to the reader
 * Quarkus REST would have chosen. The body is read by the reader Quarkus REST chooses for the wire
 * type, validated as read when the parameter asks for it, and then mapped.
 */
public class TelescopeRequestBodyReader implements ServerMessageBodyReader<Object> {

  private static final Annotation[] NO_ANNOTATIONS = {};

  private final TelescopeRestEndpoints endpoints;
  private final Providers providers;

  /**
   * @param endpoints the mappings resolved at startup
   * @param providers the readers Quarkus REST holds, which read the wire type
   */
  @Inject
  public TelescopeRequestBodyReader(final TelescopeRestEndpoints endpoints, final Providers providers) {
    this.endpoints = endpoints;
    this.providers = providers;
  }

  @Override
  public boolean isReadable(
    final Class<?> type,
    final Type genericType,
    final ResteasyReactiveResourceInfo lazyMethod,
    final MediaType mediaType
  ) {
    return lazyMethod != null && endpoints.request(lazyMethod.getMethodId()) != null;
  }

  // Only the Quarkus REST variant above claims a body, because only it names the method.
  @Override
  public boolean isReadable(
    final Class<?> type,
    final Type genericType,
    final Annotation[] annotations,
    final MediaType mediaType
  ) {
    return false;
  }

  @Override
  public Object readFrom(
    final Class<Object> type,
    final Type genericType,
    final MediaType mediaType,
    final ServerRequestContext context
  ) throws IOException {
    final var mapping = endpoints.request(context.getResteasyReactiveResourceInfo().getMethodId());
    return read(mapping, mediaType, context.getRequestHeaders().getRequestHeaders(), context.getInputStream());
  }

  @Override
  public Object readFrom(
    final Class<Object> type,
    final Type genericType,
    final Annotation[] annotations,
    final MediaType mediaType,
    final MultivaluedMap<String, String> httpHeaders,
    final InputStream entityStream
  ) {
    throw new IllegalStateException("a mapped request body is read only through the Quarkus REST reader variant");
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private Object read(
    final ResolvedBody mapping,
    final MediaType mediaType,
    final MultivaluedMap<String, String> headers,
    final InputStream body
  ) throws IOException {
    final MessageBodyReader reader = providers.getMessageBodyReader(
      mapping.wireClass(),
      mapping.wireType(),
      NO_ANNOTATIONS,
      mediaType
    );
    if (reader == null) throw new NotSupportedException(
      "No reader reads " + mapping.wireType().getTypeName() + " from " + mediaType
    );
    final var original = reader.readFrom(
      mapping.wireClass(),
      mapping.wireType(),
      NO_ANNOTATIONS,
      mediaType,
      headers,
      body
    );
    if (original == null) {
      if (mapping.required()) throw new BadRequestException("A request body is required.");
      return null;
    }
    if (mapping.validation() != null) mapping.validate(original);
    return mapping.argument(original);
  }
}
