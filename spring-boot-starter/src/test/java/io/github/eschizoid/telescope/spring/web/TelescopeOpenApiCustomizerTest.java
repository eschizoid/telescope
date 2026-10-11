package io.github.eschizoid.telescope.spring.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.spring.web.WebMvcTestSupport.PlainProjection;
import io.github.eschizoid.telescope.spring.web.fixtures.User;
import io.github.eschizoid.telescope.spring.web.fixtures.UserDto;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

class TelescopeOpenApiCustomizerTest {

  private static final User ADA = new User("1", "Ada");

  // Public because HandlerMethod(Object, String, Class...) looks the method up with
  // Class#getMethod.
  @RestController
  public static class DocController {

    @TelescopeGetMapping(value = "/docs/{id}", to = UserDto.class)
    public User user(@PathVariable final String id) {
      return ADA;
    }

    @TelescopeGetMapping(value = "/docs", to = UserDto.class)
    public List<User> users() {
      return List.of(ADA);
    }

    @TelescopeGetMapping(value = "/docs/entity/{id}", to = UserDto.class)
    public ResponseEntity<User> entity(@PathVariable final String id) {
      return ResponseEntity.ok(ADA);
    }

    @GetMapping("/docs/raw/{id}")
    public User raw(@PathVariable final String id) {
      return ADA;
    }
  }

  @Test
  void successResponsesDocumentTheMappedType() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(PlainProjection.class, DocController.class)
      .run(context -> {
        final var customizer = new TelescopeOpenApiCustomizer(context.getBean(TelescopeResponses.class), false);
        final var controller = context.getBean(DocController.class);

        final var single = customizer.customize(operation(), new HandlerMethod(controller, "user", String.class));
        assertThat(schemaOf(single).get$ref()).isEqualTo("#/components/schemas/UserDto");

        final var list = customizer.customize(operation(), new HandlerMethod(controller, "users"));
        assertThat(schemaOf(list).getItems().get$ref()).isEqualTo("#/components/schemas/UserDto");

        final var entity = customizer.customize(operation(), new HandlerMethod(controller, "entity", String.class));
        assertThat(schemaOf(entity).get$ref()).isEqualTo("#/components/schemas/UserDto");

        final var raw = customizer.customize(operation(), new HandlerMethod(controller, "raw", String.class));
        assertThat(schemaOf(raw).get$ref()).isEqualTo("#/components/schemas/User");

        final var openApi = new OpenAPI().paths(new Paths().addPathItem("/docs/{id}", new PathItem().get(single)));
        customizer.customise(openApi);
        assertThat(openApi.getComponents().getSchemas()).containsKey("UserDto");
        final Schema<?> dto = openApi.getComponents().getSchemas().get("UserDto");
        assertThat(dto.getProperties()).containsKeys("id", "fullName");

        final var unrelated = new OpenAPI();
        customizer.customise(unrelated);
        assertThat(unrelated.getComponents().getSchemas()).isNull();
      });
  }

  @Test
  void errorResponsesAreLeftAlone() {
    WebMvcTestSupport.runner()
      .withUserConfiguration(PlainProjection.class, DocController.class)
      .run(context -> {
        final var customizer = new TelescopeOpenApiCustomizer(context.getBean(TelescopeResponses.class), false);
        final var operation = operation();
        operation.getResponses().addApiResponse("404", response("#/components/schemas/Problem"));
        customizer.customize(operation, new HandlerMethod(context.getBean(DocController.class), "user", String.class));
        assertThat(operation.getResponses().get("404").getContent().get("*/*").getSchema().get$ref()).isEqualTo(
          "#/components/schemas/Problem"
        );
      });
  }

  private static Operation operation() {
    return new Operation().responses(new ApiResponses().addApiResponse("200", response("#/components/schemas/User")));
  }

  private static ApiResponse response(final String ref) {
    return new ApiResponse().content(
      new Content().addMediaType("*/*", new MediaType().schema(new Schema<>().$ref(ref)))
    );
  }

  private static Schema<?> schemaOf(final Operation operation) {
    return operation.getResponses().get("200").getContent().get("*/*").getSchema();
  }
}
