package io.github.eschizoid.telescope.spring.web;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springdoc.core.customizers.ParameterCustomizer;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

/**
 * Documents a mapped endpoint as the client sees it. Success responses show the response type
 * instead of the declared return type, and error responses keep their own schema. A mapped request
 * body shows the wire type as the operation's request body, and the parameter springdoc would
 * otherwise derive from the handler's argument is dropped. The schemas these types reference are
 * registered in each document that references them.
 */
@SuppressWarnings("rawtypes")
final class TelescopeOpenApiCustomizer
  implements GlobalOperationCustomizer, GlobalOpenApiCustomizer, ParameterCustomizer
{

  private final TelescopeResponses responses;
  private final TelescopeRequests requests;
  private final ModelConverters converters;
  private final Map<String, Schema> schemas = new ConcurrentHashMap<>();

  TelescopeOpenApiCustomizer(
    final TelescopeResponses responses,
    final TelescopeRequests requests,
    final boolean openapi31
  ) {
    this.responses = responses;
    this.requests = requests;
    this.converters = ModelConverters.getInstance(openapi31);
  }

  @Override
  public Operation customize(final Operation operation, final HandlerMethod handlerMethod) {
    documentRequestBody(operation, handlerMethod);
    final var response = responses.find(handlerMethod.getMethod());
    if (response == null || operation.getResponses() == null) return operation;
    final var schema = schemaOf(response.bodyType());
    operation
      .getResponses()
      .forEach((code, apiResponse) -> {
        if (!code.startsWith("2") || apiResponse.getContent() == null) return;
        apiResponse
          .getContent()
          .values()
          .forEach(media -> media.setSchema(schema));
      });
    return operation;
  }

  // A mapped body is read from the request, never bound from parameters.
  @Override
  public Parameter customize(final Parameter parameter, final MethodParameter methodParameter) {
    if (methodParameter.getMethod() == null) return parameter;
    final var request = requests.find(methodParameter.getMethod(), methodParameter.getParameterIndex());
    return request == null ? parameter : null;
  }

  private void documentRequestBody(final Operation operation, final HandlerMethod handlerMethod) {
    for (final var parameter : handlerMethod.getMethodParameters()) {
      final var request = requests.find(handlerMethod.getMethod(), parameter.getParameterIndex());
      if (request == null) continue;
      final var media = new MediaType().schema(schemaOf(request.wireType()));
      final var content = new Content();
      final var consumes = consumes(handlerMethod);
      if (consumes.length == 0) content.addMediaType("application/json", media);
      for (final var type : consumes) content.addMediaType(type, media);
      operation.setRequestBody(new RequestBody().required(request.required()).content(content));
      if (operation.getParameters() != null && operation.getParameters().isEmpty()) operation.setParameters(null);
    }
  }

  // As Spring maps it: the method's consumes when it declares any, else the controller's.
  private static String[] consumes(final HandlerMethod handlerMethod) {
    final var method = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequestMapping.class);
    if (method != null && method.consumes().length > 0) return method.consumes();
    final var controller = AnnotatedElementUtils.findMergedAnnotation(
      handlerMethod.getBeanType(),
      RequestMapping.class
    );
    return controller == null ? new String[0] : controller.consumes();
  }

  private Schema schemaOf(final Type type) {
    final var resolved = converters.resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(true));
    if (resolved.referencedSchemas != null) schemas.putAll(resolved.referencedSchemas);
    return resolved.schema;
  }

  // Each document, the default one and every GroupedOpenApi group, gets only the collected schemas
  // it references, directly or through another schema it receives.
  @Override
  public void customise(final OpenAPI openApi) {
    if (schemas.isEmpty()) return;
    if (openApi.getComponents() == null) openApi.setComponents(new Components());
    final var components = openApi.getComponents();
    final var references = new StringBuilder(Json.pretty(openApi));
    var added = true;
    while (added) {
      added = false;
      for (final var entry : schemas.entrySet()) {
        final var name = entry.getKey();
        final var present = components.getSchemas() != null && components.getSchemas().containsKey(name);
        if (present || references.indexOf("\"#/components/schemas/" + name + "\"") < 0) continue;
        components.addSchemas(name, entry.getValue());
        references.append(Json.pretty(entry.getValue()));
        added = true;
      }
    }
  }
}
