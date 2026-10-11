package io.github.eschizoid.telescope.spring.web;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springframework.web.method.HandlerMethod;

/**
 * Documents a mapped endpoint's success responses with the response type instead of the declared
 * return type, and registers the schemas that type references. Error responses keep their own
 * schema.
 */
@SuppressWarnings("rawtypes")
final class TelescopeOpenApiCustomizer implements GlobalOperationCustomizer, GlobalOpenApiCustomizer {

  private final TelescopeResponses responses;
  private final ModelConverters converters;
  private final Map<String, Schema> schemas = new ConcurrentHashMap<>();

  TelescopeOpenApiCustomizer(final TelescopeResponses responses, final boolean openapi31) {
    this.responses = responses;
    this.converters = ModelConverters.getInstance(openapi31);
  }

  @Override
  public Operation customize(final Operation operation, final HandlerMethod handlerMethod) {
    final var response = responses.find(handlerMethod.getMethod());
    if (response == null || operation.getResponses() == null) return operation;
    final var resolved = converters.resolveAsResolvedSchema(new AnnotatedType(response.bodyType()).resolveAsRef(true));
    if (resolved.referencedSchemas != null) schemas.putAll(resolved.referencedSchemas);
    operation
      .getResponses()
      .forEach((code, apiResponse) -> {
        if (!code.startsWith("2") || apiResponse.getContent() == null) return;
        apiResponse
          .getContent()
          .values()
          .forEach(media -> media.setSchema(resolved.schema));
      });
    return operation;
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
