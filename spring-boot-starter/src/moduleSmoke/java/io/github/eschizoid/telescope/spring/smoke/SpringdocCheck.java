package io.github.eschizoid.telescope.spring.smoke;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.web.method.HandlerMethod;

/**
 * Loaded only in the full run: the springdoc customizer is registered and documents the response
 * type.
 */
final class SpringdocCheck {

  private SpringdocCheck() {}

  static void run(final ApplicationContext context) throws NoSuchMethodException {
    final var customizers = context.getBeansOfType(OperationCustomizer.class);
    if (customizers.size() != 1) throw new AssertionError("expected one springdoc customizer: " + customizers.keySet());
    final var controller = context.getBean(ModuleSmoke.AccountController.class);
    final var operation = new Operation().responses(
      new ApiResponses().addApiResponse(
        "200",
        new ApiResponse().content(
          new Content().addMediaType("*/*", new MediaType().schema(new Schema<>().$ref("#/components/schemas/Account")))
        )
      )
    );
    customizers.values().iterator().next().customize(operation, new HandlerMethod(controller, "account", String.class));
    final var ref = operation.getResponses().get("200").getContent().get("*/*").getSchema().get$ref();
    if (!"#/components/schemas/AccountView".equals(ref)) throw new AssertionError("documented as " + ref);
  }
}
