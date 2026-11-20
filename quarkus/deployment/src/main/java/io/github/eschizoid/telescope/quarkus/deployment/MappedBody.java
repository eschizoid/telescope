package io.github.eschizoid.telescope.quarkus.deployment;

import io.github.eschizoid.telescope.quarkus.rest.runtime.ValueShape;
import java.util.List;

/**
 * One mapped body the build found, with every type named by its binary name. The runtime receives
 * it as a {@code MappedEndpoint}, once for each resource class that routes the method.
 *
 * @param methodIds the method's id for each resource class that routes it, as Quarkus REST keys a
 *     resource method
 * @param where the method, and for a request the parameter, as a refusal names it
 * @param annotation the annotation's name as a refusal names it
 * @param request whether this is a request body rather than a response
 * @param shape where the values sit in the body
 * @param source the type the mapping reads, or {@code null} when {@code using} supplies it
 * @param target the type the mapping writes, or {@code null} when {@code using} supplies it
 * @param using the projection to map through, or {@code null}
 * @param holder whether the parameter is a {@code TelescopeRequest}
 * @param required whether a request body is required
 * @param validated whether the body is validated as read
 * @param validationPath the property path a violation of a request body as read starts with, as
 *     Quarkus names a parameter of a resource method, or {@code null} for a response
 */
public record MappedBody(
  List<String> methodIds,
  String where,
  String annotation,
  boolean request,
  ValueShape shape,
  String source,
  String target,
  String using,
  boolean holder,
  boolean required,
  boolean validated,
  String validationPath
) {}
